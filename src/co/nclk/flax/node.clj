(ns co.nclk.flax.node
  (:require [clojure.tools.logging :refer [log]]
            [clj-ssh.ssh :as ssh])
  (:import com.jcraft.jsch.JSch
           com.jcraft.jsch.Logger
           clj_ssh.ssh.SshLogger)
  (:gen-class))

(JSch/setLogger (SshLogger. Logger/ERROR))

(defprotocol PNode
  (proxy [self config])
  (create [self node full-name])
  (destroy [self])
  (clone [self]))


(defprotocol PNodeManager
  (get-node [self node])
  (invoke [self node-label checkpoint])
  (full-node-name [self node])
  (remove-node [self node])
  (isolate [self]))


(def ^:dynamic *log* false)


(defn node-log-str
  [node-name ip runid & more]
  (str "[" node-name (if ip (str " (" ip ")") "") ": " runid "] " (apply str more)))


(defn node-error
  [body node]
  (clojure.string/join "\n"
    [body
     (str "Node: " (:name node))
     (str "Hostname: " (:public_ip node))]))


(defn resolve-connector
  [namesp]
  (-> namesp symbol require)
  (-> namesp (str "/connector") symbol resolve))


(defrecord LocalNode []
  PNode
  (create [self node full-name] self)
  (destroy [self] nil)
  (clone [self] self))


(defn log-result
  [out err exit node-name ip runid]
  (when-not (clojure.string/blank? out)
    (log :debug (node-log-str node-name ip runid "stdout: " (clojure.string/trim out))))
  (when-not (clojure.string/blank? err)
    (log :debug
      (node-log-str node-name ip runid "stderr: " (clojure.string/trim err)))))


(defn- invoke-local
  [checkpoint & [argv]]
  (binding [*log* (not (false? (:log checkpoint)))]
    (let [tmpfile-name (str (System/getProperty "user.dir") "/.flax-temp-script-" (java.util.UUID/randomUUID))
          proxy? (not (nil? argv))
          argv (or argv
                 (remove clojure.string/blank?
                   (flatten
                     [(if-let [u (:user checkpoint)] ["sudo" "-u" u])
                      (if-let [d (:invocation checkpoint)]
                        (clojure.string/split
                          (if-let [t (:template d)]
                            (if-not (clojure.string/blank? (:match d))
                              (clojure.string/replace t (re-pattern (str (:match d))) tmpfile-name)
                              (str t " " tmpfile-name))
                            (str d " " tmpfile-name))
                          #" ")
                        tmpfile-name
                        )])))]
      (when-not proxy?
        (spit tmpfile-name (:source checkpoint))
        (-> (java.io.File. tmpfile-name) (.setExecutable true))
        (Thread/sleep 100))

      (let [proc (-> (Runtime/getRuntime)
                   (.exec (into-array String argv)))
            stdout (clojure.java.io/reader (.getInputStream proc))
            stderr (clojure.java.io/reader (.getErrorStream proc))]

        (when *log*
          (when (:display checkpoint)
            (log :info (node-log-str "local" nil (:runid checkpoint) (clojure.string/trim (:display checkpoint)))))
          (log :debug (node-log-str "local" nil (:runid checkpoint) (clojure.string/join " " argv)))
          (log :debug (node-log-str "local" nil (:runid checkpoint) "Contents of " tmpfile-name ":\n" (:source checkpoint))))

        (let [[out err]
              (for [stream [stdout stderr]]
                (future
                  (loop [lines []]
                    (let [line (.readLine stream)]
                      (if (nil? line)
                        lines
                        (do
                          (log :debug (node-log-str "local" nil (:runid checkpoint) line))
                          (recur (conj lines line))))))))]

          (let [exit (.waitFor proc)
                out (clojure.string/join "\n" @out)
                err (clojure.string/join "\n" @err)
                result
                (assoc checkpoint :out {:keys (:out checkpoint) :value out}
                                  :err {:keys (:err checkpoint) :value err}
                                  :exit {:keys (:exit checkpoint) :value exit})]
            (when-not proxy? (clojure.java.io/delete-file tmpfile-name))
            (when-not (zero? exit)
              (log-result nil nil exit "local" nil (:runid checkpoint)))
            result))))))


(defn- invoke-remote
  [checkpoint node]
  (if (:proxy checkpoint)
    (invoke-local checkpoint (proxy node (:proxy checkpoint)))
    (if (:source checkpoint)
      (binding [*log* (not (false? (:log checkpoint)))]
        (let [node (:data node)
              agent (:ssh-agent @node)
              total-attempts 3]
          (loop [remaining-attempts total-attempts]
            (if (zero? remaining-attempts)
              (assoc checkpoint :out {:keys (:out checkpoint) :value ""}
                                :err {:keys (:err checkpoint)
                                      :value (str "flax: " total-attempts " attempts to ssh to " (:name @node) " failed.")}
                                :exit {:keys (:exit checkpoint) :value 1})
              (if-let [resolved-checkpoint
                       (try
                         (let [session (ssh/session
                                         agent
                                         (:public_ip @node)
                                         {:strict-host-key-checking :no
                                          :log-level :quiet
                                          :user-known-hosts-file (or (-> @node :options :user-known-hosts-file) "/dev/null")
                                          :username (-> @node :options :user)})]
                           (when (and *log*
                                      (not (clojure.string/blank? (:display checkpoint))))
                             (log :info
                               (node-log-str (:short-name @node)
                                             (:public_ip @node)
                                             (:runid checkpoint)
                                             (clojure.string/trim (:display checkpoint)))))

                           (ssh/with-connection session
                             (when *log*
                               (log :debug (node-log-str (:short-name @node)
                                                         (:public_ip @node)
                                                         (:runid checkpoint)
                                                         (clojure.string/trim (:source checkpoint)))))
                             (let [result (ssh/ssh session {:cmd (str "sudo su " (or (:user checkpoint) "root") " - ")
                                                            :in (:source checkpoint)})]
                               (when *log*
                                 (log-result (:out result)
                                             (:err result)
                                             (:exit result)
                                             (:short-name @node)
                                             (:public_ip @node)
                                             (:runid checkpoint)))

                               (assoc checkpoint :out {:keys (:out checkpoint)
                                                       :value (:out result)}
                                                 :err {:keys (:err checkpoint)
                                                       :value (:err result)}
                                                 :exit {:keys (:exit checkpoint)
                                                        :value (:exit result)}))
                               ))
                         (catch Exception se
                           (log :error
                             (node-log-str (:short-name @node)
                                           (:public_ip @node)
                                           (:runid checkpoint)
                                           (.getMessage se)
                                           ". "
                                           remaining-attempts
                                           " attempts remaining."))
                           nil))]
                resolved-checkpoint
                (recur (dec remaining-attempts))))))
                ))))

(defn short-name
  [node]
  (cond
    (string? node)
    node

    (or (nil? (:name node))
        (= (name (:name node)) "local"))
    "local"

    :else (:name node)))

(defrecord NodeManager [nodes effective version context]
  PNodeManager
  (get-node [self node]
    (cond

      ;; If you're only passing a string, the only outcome can be
      ;; retrieving an existing node (or nil).
      (string? node)
      (-> @nodes (get (full-node-name self node)))

      (map? node)
      (or ;; If a node already exists with the node name, just return it.
          (-> @nodes (get (full-node-name self node)))
          (and (nil? (-> node :connector))
               (do (log :warn (format "flax: connector not found for node: %s. Using \"local\" instead." (:name node)))
                   (-> @nodes (get "local"))))
          ;; Else, create the node and add it to the list of managed nodes.
          (let [ctor (-> node :connector resolve-connector)
                n (-> (ctor context) (create node (full-node-name self node)))
                agent (ssh/ssh-agent {})]
            (ssh/add-identity agent {:name (:name @(:data @n)) :private-key (-> @(:data @n) :private-key)})
            (swap! (:data @n) #(assoc % :ssh-agent agent
                                        :short-name (short-name node)))
            ;; The `node` returned from the constructor is a promise.
            ;; The new node record has some `:data` with a `:name` entry
            ;; which is the full node name. We add the node to the manager's
            ;; nodes with this full name as the key.
            (swap! nodes #(assoc % (-> @(:data @n) :name)
                                   @n))
            @n))))
  (invoke [self checkpoint node]
    (let [ts (java.util.Date.)]
      (assoc (if (= "local" (full-node-name self node))
               (invoke-local checkpoint)
               (let [node (get-node self node)]
                 (if (nil? (:data node))
                   (invoke-local checkpoint)
                   (invoke-remote checkpoint node))))
             :started (.getTime ts)
             :finished (.getTime (java.util.Date.))))
    )
  (remove-node [self node] nodes)
  (full-node-name [self node]
    (cond
      (string? node)
      node

      (or (clojure.string/blank? (:name node))
          (= (name (:name node)) "local"))
      "local"

      :else (str (name (:name node)) "-" (.getTime (:effective self)) "-" (:version self))))
  (isolate [self] self))


(defn node-manager
  [effective & [version]]
  (NodeManager. (atom {"local" (LocalNode.)})
                effective
                (or version 0)
                (atom {})))

