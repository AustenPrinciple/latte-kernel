(ns latte-kernel.nbe
  (:require [latte-kernel.utils :refer [vector*]]
            [latte-kernel.syntax :as stx]
            [clojure.set :as set]))

;;{
;; # Normalisation by evaluation
;;
;; Instead of applying ourselves the normalisation as seen in `norm.cljc`,
;; we want to let the Clojure language do for us the complicated part:
;; substituting argument names for their value while respecting nested scopes,
;; aka calling the λ-functions.
;;
;; This is done in three steps:
;; 1. translating terms into a nbe-specific syntax, in which
;;    λ-expressions are replaced by Clojure functions.
;; 2. normalising those terms, aka applying the functions wherever possible.
;; 3. retranslating back into normal λ-terms.
;;
;;Those three steps are represented here with the functions
;; `evaluation`, `normalisation`, and `quotation`, all summed up into `norm`.
;;
;; It is important to note that `evaluation` works recursively, depth-first.
;;
;; ### Delayed normalisation:
;; One major trick used here is the following: <br>
;; In this valid lambda expression `(λ [a ✳] [(λ [b ✳] [a b]) c])`
;; which would be normalised into `(λ [a ✳] [a c])`,
;; we can't directly tell Clojure to create the function `(fn [b] [a b])`,
;; since `a` isn't defined in this context.
;; We therefore can't make the normalisation part recursively & depth-first too. <br>
;; The trick is using the power of the Lisp quote to delay the call to
;; the inner normalisation, by creating (in `evaluation`) a quoted function that
;; will, only when called, call `normalisation` on its previously-evaluated body. <br>
;; This makes it so that `eval`-ing the outer function actually processes
;; the normalisation of its body, which means applying the inner function. <br>
;; In our example, the outer function `(fn [a] ((fn [b] [a b]) c))` is not
;; actually called during normalisation, and therefore the associated body
;; is not yet reduced. This is done in the final function `quotation`:
;; if we find a λ-expression after normalisation, we can then 'pretend-call' it
;; with its initial argument, and write out the result. <br>
;; In this case we would call the outer function with
;; parameter `a` (the symbol), which would trigger the delayed normalisation.
;;
;; Because of this, the functions `evaluation` and `normalisation` need to be
;; defined in reverse order, since the former uses the latter.
;;
;; ### Bound variables
;; Even if we don't have to manually handle the bound variables and their
;; respective scopes, we still have to mark
;;TODO: finish doc, talk about bound and unbound variables
;;
;;}

(defn qualif-keyword
  "Turn symbol 'x' into a qualified keyword with the current namespace.
  Placeholder for when I figure out how *ns* really works.
  (It seem to only return 'user' at runtime)"
  [x]
  (keyword "latte-kernel.nbe" (name x)))

(defn normalisation
  "Actually apply normalisation, staying in nbe-specific syntax."
  [t]
  (if (or (stx/variable? t) (stx/host-constant? t) (keyword? t))
    t
    (case (first t)
      ::lambda (let [[kw var-name var-type f] t]
                 [kw var-name (normalisation var-type) (eval f)])
      ::pi (let [[kw var-name var-type body] t]
             [kw var-name (normalisation var-type) (normalisation body)])
      ::app (let [[_ l r] t
                  l' (normalisation l)
                  r' (normalisation r)]
              (if (and (vector? l') (= ::lambda (first l')))
                ;; We can apply the function contained in l'
                ((last l') r')
                [::app l' r']))
      ::ref (vector* ::ref (second t) (map normalisation (drop 2 t)))
      ::asc (vector* ::asc (map normalisation (rest t))))))

(defn evaluation
  "Convert from LaTTe internal syntax to nbe-specific syntax, recursively.
  All simple terms are keywordized, and composite terms are translated into
  a pair with a special keyword, excepts lambdas which are turned into real
  Clojure functions.
  Variables which were seen on the way down are marked as 'bound' and are not
  translated at all to allow calling directly the functions."
 ([t] (evaluation t #{} #{} #{}))
 ([t lambda-bound pi-bound free]
  {:pre [(stx/term? t)]}
  (let [eva (fn [te] (evaluation te lambda-bound pi-bound free))
        s (set/union lambda-bound pi-bound free)
        _ (println "l" lambda-bound "p" pi-bound "f" free)]
    (cond
      ;; variable
      (stx/variable? t)
      (let [vname (stx/fetch-last-fresh t s)
            _ (println "Encountering" t "as" vname)]
        (cond
          (contains? lambda-bound vname) vname
          (contains? pi-bound vname) (qualif-keyword vname)
          :free (qualif-keyword vname)))

      ;; sort
      (stx/sort? t)
      (qualif-keyword t)

      ;; lambda
      (stx/lambda? t)
      (let [[_ [x tx] body] t
            x' (stx/mk-fresh x s)
            body' (evaluation body (conj lambda-bound x') pi-bound free)]
        ;; TODO: update comment
        ;; We create a quoted function and attach as metadata the name originally
        ;; used as argument, and its type. Useful later for quotation.
        ;; We also add a delayed call to normalisation to be able to reduce
        ;; nested expressions
        [::lambda (qualif-keyword x') (eva tx) `(fn [~x'] (normalisation ~body'))])

      ;; pi
      (stx/prod? t)
      (let [[_ [x tx] body] t
            x' (stx/mk-fresh x s)
            body' (evaluation body lambda-bound (conj pi-bound x') free)]
        [::pi (qualif-keyword x') (eva tx) body'])

      ;; reference
      (stx/ref? t)
      (vector* ::ref (first t) (map eva (rest t)))

      ;; application
      (stx/app? t)
      (vector* ::app (map eva t))

      ;; ascription
      (stx/ascription? t)
      (vector* ::asc (map eva (rest t)))

      ;; host constants
      (stx/host-constant? t) ;t
      (throw (ex-info "Don't know what to do with host-constant"
               {:host-constant t}))))))

(defn quotation
  "From nbe-specific syntax to normal LaTTe internal syntax."
  [t]
  {:post [(stx/term? %)]}
  (cond
    (stx/variable? t)  t
    (keyword? t) (symbol (name t))
    :default
    (case (first t)
      ::lambda (let [[_ var-name var-type f] t]
                 ;; TODO: update comment
                 ;; f has never been called, so the body wasn't reduced.
                 ;; We call it with the associated variable to unfold everything
                 (list 'λ [(quotation var-name) (quotation var-type)]
                   (quotation (f var-name))))
      ::pi (let [[_ var-name var-type body] t]
             (list 'Π [(quotation var-name) (quotation var-type)]
               (quotation body)))
      ::app (vec (map quotation (rest t)))
      ::ref (cons (second t) (map quotation (rest (rest t))))
      ::asc (cons ::stx/ascribe (map quotation (rest t))))))

(defn norm
  "Compose above functions to create the 'normalisation by evaluation' process."
  [t]
  {:pre [(stx/term? t)]
   :post [(stx/term? %), (nil? (meta %))]}
  (->> t stx/free-vars (evaluation t #{} #{}) normalisation quotation))
