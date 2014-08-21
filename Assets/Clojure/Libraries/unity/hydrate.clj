(ns unity.hydrate
  (:require [unity.map-utils :as mu]
            [unity.seq-utils :as su]
            [unity.reflect-utils :as ru]
            [clojure.string :as string])
  (:import UnityEditor.AssetDatabase
           [System.Reflection Assembly]
           System.AppDomain))

(declare hydration-database)

(defmacro cast-as [x type]
  (let [xsym (with-meta (gensym "caster_") {:tag type})]
    `(let [~xsym ~x] ~xsym)))

;; seem to be fucking up backquoted macro references?

(defn camels-to-hyphens [s]
  (string/replace s #"([a-z])([A-Z])" "$1-$2"))

(defn type? [x]
  (instance? System.MonoType x))

(defn nice-keyword [s]
  (keyword
    (clojure.string/lower-case
      (camels-to-hyphens (name s)))))

(defn ensure-type [t]
  (cond
    (symbol? t) (resolve t)
    (type? t) t
    :else (throw
            (ArgumentException.
              (str "Expects symbol or type, instead (type t) = "
                (type t))))))

(defn type-symbol [^System.MonoType t]
  (symbol (.FullName t)))

(defn ensure-type-symbol [x]
  (cond
    (symbol? x) x
    (type? x) (type-symbol x)
    :else (throw
            (ArgumentException.
              (str "Expects symbol or type, instead (type t) = "
                (type x))))))

(defn keyword-for-type [t]
  (nice-keyword
    (.name ^System.MonoType
      (ensure-type t))))

;; generate more if you feel it worth testing for
(defn keys-for-setable [{n :name}]
  [(nice-keyword n)])

(defn type-for-setable [{typ :type}]
  typ) ;; good enough for now

(defn setable-properties [typ]
  (ru/properties (ensure-type typ)))

(defn setable-fields [typ]
  (->> typ
    ensure-type
    ru/fields
    (filter
      (fn [{fs :flags}]
        (and
          (:public fs)
          (not (:static fs)))))))

(defn dedup-by [f coll]
  (map peek (vals (group-by f coll))))

(defn setables [typ]
  (dedup-by :name
    (concat
      (setable-properties typ)
      (setable-fields typ))))

(defn hydration-form [hdb type vsym]
  (assert (symbol? type))
  (if-let [hff (get-in hdb [:hydration-form-fns type])]
    (hff vsym)
    `(cast-as ~vsym ~type)))

;; insert converters etc here if you feel like it
(defn setter-key-clauses [hdb targsym typ vsym]
  (let [valsym (with-meta (gensym) {:tag typ})]
    (apply concat
      (for [{n :name, :as setable} (setables typ)
            :let [styp (type-for-setable setable)
                  vhyd (hydration-form hdb styp vsym)]
            k  (keys-for-setable setable)]
        `[~k (set! (. ~targsym ~n) ~vhyd)]))))

(defn setter-reducing-fn-form [hdb ^System.MonoType typ]
  (let [ksym (gensym "spec-key_")
        vsym (gensym "spec-val_")
        targsym (with-meta (gensym "targ")
                  {:tag typ})
        skcs (setter-key-clauses hdb targsym typ vsym)
        fn-inner-name (symbol (str "setter-fn-for-" typ))]
    `(fn ~fn-inner-name ~[targsym ksym vsym] 
       (case ~ksym
         ~@skcs
         ~targsym))))

(defn setter-form [hdb typ]
  (let [typ      (ensure-type-symbol typ)
        targsym  (with-meta (gensym "setter-target") {:tag typ})
        specsym  (gensym "spec")
        sr       (setter-reducing-fn-form hdb typ)]
    `(fn [~targsym spec#]
       (reduce-kv
         ~sr
         ~targsym
         spec#))))

(defn generate-setter [hdb type]
  (eval (setter-form hdb type)))

(defn all-component-types []
  (->>
    (.GetAssemblies AppDomain/CurrentDomain)
    (mapcat #(.GetTypes %))
    (filter #(isa? % UnityEngine.Component))))

(defn all-component-type-symbols []
  (map type-symbol (all-component-types)))

(defn expand-map-to-type-kws [m]
  (merge m
    (-> m
      (mu/filter-keys type?)
      (mu/map-keys keyword-for-type))))

(defn build-setters [hydration-form-fns types]
  (expand-map-to-type-kws
    (zipmap types
      (map #(generate-setter hydration-form-fns %)
        types))))

(defn build-hydration-database [{hffs :hydration-form-fns
                                 strs :setters
                                 :or {hffs {}
                                      strs {}}
                                 :as hdb0},
                                types]
  (mu/merge-in hdb0 (build-setters hffs types)))

(defn refresh-hydration-database []
  (swap! hydration-database
    (fn [db]
      (build-hydration-database db
        (all-component-type-symbols)))))

(comment ;; this works well, but each type takes about 500
         ;; milliseconds, and there are 80 built-in component types
         ;; alone. hrm. would it be that slow if we did it as a macro?
  (refresh-hydration-database))
;;; fdf

(defmacro refresh-hydration-database-as-a-macro
  ([& [n]]
     (let [types (if (or (not n) (= n :all))
                   (all-component-types)
                   (take n (all-component-types)))
           sfs   (map setter-form types)]
       `(let [ts# [~@types]]
          (reset! hydration-database 
            (zipmap [~@types]
              [~@sfs]))))))

(defn register-component [type]
  (let [s (generate-setter type)]
    (swap! hydration-database ;; maybe it should be an agent
      (fn [db]
        (assoc db type s)))))

(comment
  (defn set-members [c spec]
    ((setter spec) c spec))

  (defn hydrate-component [^GameObject obj, spec]
    (set-members (initialize-component obj, spec) spec)))

;; ============================================================
;; some other setter defs
;; ============================================================

;; vector things
(defmacro def-vectorish-hydrater [name type field-args]
  (let [argsym (with-meta (gensym "arg_") {:tag type})]
    `(defn ~name ~(with-meta [argsym]
                    {:tag type})
       (-> (cond
             (instance? ~type ~argsym)
             ~argsym

             (vector? ~argsym)
             (let [[~@field-args] ~argsym]
               (new ~type ~@field-args))
             
             :else
             (let [{:keys [~@field-args]
                    :or ~(zipmap field-args (repeat 0))} ~argsym]
               (new ~type ~@field-args)))
         (cast-as ~type)))))

(def-vectorish-hydrater vec2-hyd, UnityEngine.Vector2, [x y])

(def-vectorish-hydrater vec3-hyd, UnityEngine.Vector3, [x y z])

(def-vectorish-hydrater vec4-hyd, UnityEngine.Vector4, [x y z w])

(def-vectorish-hydrater quat-hyd, UnityEngine.Quaternion, [x y z w])

;; ============================================================
;; the hydration database itself
;; ============================================================

(def hydration-database
  (atom
    {:hydration-form-fns
     (->
       `{UnityEngine.Vector2    vec2-hyd
         UnityEngine.Vector3    vec3-hyd
         UnityEngine.Vector4    vec4-hyd
         UnityEngine.Quaternion quat-hyd}
       (mu/map-vals
         (fn [fsym]
           (fn [vsym]
             `(~fsym ~vsym)))))
     :setters {}}))


