(ns via-schema.core-test
  (:require [via-schema.core :as vsc]
            [via.endpoint :as via]
            [via.subs :as vs]
            [via.events :as ve]
            [via.defaults :refer [default-via-endpoint]]
            [via.core :as vc]

            [clojure.data :refer [diff]]

            [signum.subs :as ss]
            [signum.signal :as sig]
            [signum.events :as se]
            [signum.fx :as sfx]

            [integrant.core :as ig]

            [compojure.core :as compojure :refer [GET POST]]
            [compojure.route :as route]
            [ring.middleware.defaults :as ring-defaults]

            [utilis.timer :as timer]
            [utilis.map :refer [map-vals]]

            [clojure.test :as t]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop :include-macros true]
            [clojure.test.check.clojure-test :refer [defspec]]
            [via.adapter :as adapter])
  (:import [java.net ServerSocket]))

;;; Endpoint Setup

(def lock (Object.))

(defn- allocate-free-port!
  []
  (locking lock
    (let [socket (ServerSocket. 0)]
      (.setReuseAddress socket true)
      (let [port (.getLocalPort socket)]
        (try (.close socket) (catch Exception _))
        port))))

(defmethod ig/init-key :via.core-test/ring-handler
  [_ {:keys [via-handler]}]
  (-> (compojure/routes
       (GET default-via-endpoint ring-req (via-handler ring-req)))
      (ring-defaults/wrap-defaults ring-defaults/site-defaults)))

(defn default-event-listener
  [[event-id event]]
  (when (not (#{:via.endpoint.peer/connected
                :via.endpoint.peer/disconnected
                :via.endpoint.peer/removed} event-id))
    (locking lock
      (println event-id event))))

(defn peer
  ([] (peer nil))
  ([{:keys [port integrant-config] :as endpoint-config}]
   (let [endpoint-config (dissoc endpoint-config :port :integrant-config)]
     (loop [attempts 3]
       (let [result (try (let [port (or port (allocate-free-port!))
                               peer (ig/init
                                     (merge {:via/endpoint endpoint-config
                                             :via/subs {:endpoint (ig/ref :via/endpoint)}
                                             :via/http-server {:ring-handler (ig/ref :via.core-test/ring-handler)
                                                               :http-port port}
                                             :via.core-test/ring-handler {:via-handler (ig/ref :via/endpoint)}}
                                            integrant-config))]
                           {:peer peer
                            :port port
                            :endpoint (:via/endpoint peer)
                            :shutdown #(ig/halt! peer)
                            :address (str "ws://localhost:" port default-via-endpoint)})
                         (catch Exception e
                           (if (zero? attempts)
                             (throw e)
                             ::recur)))]
         (if (not= result ::recur)
           result
           (recur (dec attempts))))))))

(defn shutdown
  [{:keys [shutdown] :as peer}]
  (shutdown))

(defn connect
  [from to]
  (via/connect (:endpoint from) (:address to)))

(defn wait-for
  ([p] (wait-for p 5000))
  ([p timeout-ms]
   (let [result (deref p timeout-ms ::timed-out)]
     (if (= result ::timed-out)
       (throw (ex-info "Timed out waiting for promise" {}))
       result))))

;;; Tests

;; VIA_SCHEMA
;; - test bad schemas don't pass
;; - test that good schemas do pass

(defspec strip-inbound-unknown-keys
  30
  (prop/for-all [value (gen/map gen/keyword gen/any-printable-equatable)
                 known-value gen/keyword]
                (let [value (assoc value :known-value known-value)
                      event-id (str (gensym) "/event")
                      peer-1 (peer {:exports {:events #{event-id}}})
                      peer-2 (peer)]
                  (se/reg-event
                   event-id
                   (vsc/>fn
                    [_ [_ value]]
                    [_ [:tuple _ [:map [:known-value :keyword]]] => any?]
                    {:via/reply {:status 200
                                 :body value}}))
                  (try (= {:known-value known-value}
                          (:body @(vc/dispatch
                                   (:endpoint peer-2)
                                   (connect peer-2 peer-1)
                                   [event-id value])))
                       (catch Exception e
                         (locking lock
                           (println e))
                         false)
                       (finally
                         (shutdown peer-1)
                         (shutdown peer-2))))))

(defspec strip-outbound-unknown-keys
  30
  (prop/for-all [value (gen/map gen/keyword gen/any-printable-equatable)
                 known-value gen/keyword]
                (let [value (assoc value :known-value known-value)
                      event-id (str (gensym) "/event")
                      peer-1 (peer {:exports {:events #{event-id}}})
                      peer-2 (peer)]
                  (se/reg-event
                   event-id
                   (vsc/>fn
                    [_ [_ value]]
                    [_ [:tuple _ any?] => [:map [:known-value :keyword]]]
                    {:via/reply {:status 200
                                 :body value}}))
                  (try (= {:known-value known-value}
                          (:body @(vc/dispatch
                                   (:endpoint peer-2)
                                   (connect peer-2 peer-1)
                                   [event-id value])))
                       (catch Exception e
                         (locking lock
                           (println e))
                         false)
                       (finally
                         (shutdown peer-1)
                         (shutdown peer-2))))))

(defspec fail-bad-inbound-schema
  30
  (prop/for-all [value (gen/map gen/keyword gen/any-printable-equatable)]
                (let [event-id (str (gensym) "/event")
                      peer-1 (peer {:exports {:events #{event-id}}})
                      peer-2 (peer)]
                  (se/reg-event
                   event-id
                   (vsc/>fn
                    [_ [_ value]]
                    [_ [:tuple _ [:map [:known-value :keyword]]] => any?]
                    {:via/reply {:status 200
                                 :body value}}))
                  (try (let [reply (try @(vc/dispatch
                                          (:endpoint peer-2)
                                          (connect peer-2 peer-1)
                                          [event-id {:bad-value value}])
                                        (catch Exception e
                                          (:error (ex-data e))))]
                         (and (= :inbound-schema-validation-error (:error (:body reply)))
                              (= 400 (:status reply))))
                       (catch Exception e
                         (locking lock
                           (println e))
                         false)
                       (finally
                         (shutdown peer-1)
                         (shutdown peer-2))))))

(defspec fail-bad-outbound-schema
  30
  (prop/for-all [value (gen/map gen/keyword gen/any-printable-equatable)]
                (let [event-id (str (gensym) "/event")
                      peer-1 (peer {:exports {:events #{event-id}}})
                      peer-2 (peer)]
                  (se/reg-event
                   event-id
                   (vsc/>fn
                    [_ [_ value]]
                    [_ [:tuple _ _] => [:map [:known-value :keyword]]]
                    {:via/reply {:status 200
                                 :body {:bad-value value}}}))
                  (try (let [reply (try @(vc/dispatch
                                          (:endpoint peer-2)
                                          (connect peer-2 peer-1)
                                          [event-id value])
                                        (catch Exception e
                                          (:error (ex-data e))))]
                         (and (= :outbound-schema-validation-error (:error (:body reply)))
                              (= 500 (:status reply))))
                       (catch Exception e
                         (locking lock
                           (println e))
                         false)
                       (finally
                         (shutdown peer-1)
                         (shutdown peer-2))))))
