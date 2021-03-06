(ns via-schema.core
  (:require [aave.core :as a]
            [aave.code :as code]
            [aave.syntax.ghostwheel :as syntax.gw]
            [malli.core :as m]
            [malli.error :as me]
            [malli.transform :as mt]
            [utilis.fn :refer [fsafe]]
            [clojure.walk :refer [postwalk]]))

;;; Declarations

(declare tx-ret-schema remap-underscores coerce-body generate)

;;; API

(defmacro >fn
  "Args are the same as >defn from aave (https://github.com/teknql/aave), but
  without a function name (as nothing will be defined at the namespace level)."
  {:arglists '([doc-string? attr-map? [params*] [schemas*]? body])}
  [& args]
  (let [cfg (-> (symbol (str "validate-" (gensym)))
                (cons args)
                (syntax.gw/parse)
                (merge {:private false})
                (update :meta-map (partial merge {::coerce true})))
        ret? (boolean (:ret-schema cfg))
        {:keys [ret-schema param-schema]
         :as cfg} (cond-> cfg
                    ret? (update :ret-schema remap-underscores)
                    (:param-schema cfg) (update :param-schema remap-underscores))
        cfg (cond-> cfg
              ret? (-> (assoc :orig-ret-schema ret-schema)
                       (update :ret-schema tx-ret-schema)))]
    `(generate ~cfg)))

;;; Implementation

(def underscore (symbol "_"))
(def underscore? (partial = underscore))

(defn- remap-underscores
  [x]
  (postwalk #(if (underscore? %) 'any? %) x))

(defn tx-ret-schema
  [schema]
  [:multi {::outstrument true
           :dispatch '(fn [result]
                        (if (and (map? result)
                                 (contains? result :via/reply))
                          (if (= 200 (-> result :via/reply :status))
                            :via/reply-ok
                            :via/reply-error)
                          :via/sub))}
   [:via/reply-ok [:map [:via/reply
                         [:map
                          [:status :int]
                          [:body {:optional true} schema]]]]]
   [:via/reply-error 'any?]
   [:via/sub schema]])

(def transformer
  (mt/transformer
   (mt/strip-extra-keys-transformer)
   (mt/default-value-transformer)
   (mt/json-transformer)))

(defmacro generate
  [{:keys [meta-map param-schema orig-ret-schema ret-schema] :as config}]
  (let [{::keys [coerce]} meta-map]
    `(let [f# (code/generate ~config)
           coerce-params# (if (and ~coerce ~param-schema) (m/decoder ~param-schema transformer) identity)
           coerce-body# (if (and ~coerce ~ret-schema) (m/decoder ~ret-schema transformer) identity)]
       (fn [& args#]
         (try (let [result# (->> args#
                                 (into [])
                                 coerce-params#
                                 (apply f#))]
                (if (and (and (map? result#)
                              (contains? result# :via/reply))
                         (= 200 (:status (:via/reply result#))))
                  (update-in result# [:via/reply :body] coerce-body#)
                  (coerce-body# result#)))
              (catch Exception e#
                (or (when-let [data# (ex-data e#)]
                      (when-let [schema# (some-> (:schema data#) m/form)]
                        (let [event?# (or (and (:value data#)
                                               (map? (:value data#))
                                               (contains? (:value data#) :via/reply))
                                          (and (= 2 (count args#))
                                               (map? (first args#))
                                               (contains? (first args#) :coeffects)
                                               (contains? (first args#) :event)))
                              outbound?# (and (= :multi (first schema#))
                                              (::outstrument (second schema#)))
                              query# (if event?#
                                       (vec (second args#))
                                       (vec args#))
                              error-body# (if outbound?#
                                            {:error :outbound-schema-validation-error
                                             :message "Outstrument schema validation error."
                                             :event query#
                                             :explain {:schema (m/form ~orig-ret-schema)}}
                                            {:error :inbound-schema-validation-error
                                             :message "Instrument schema validation error."
                                             :event query#
                                             :explain {:schema (last schema#)
                                                       :value (second (:value data#))}})]
                          (if event?#
                            {:via/reply {:status (if outbound?# 500 400)
                                         :body error-body#}}
                            (throw (ex-info (:message error-body#) data#))))))
                    (throw e#))))))))
