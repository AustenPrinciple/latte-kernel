(ns latte-kernel.nbe-test
  (:require #?(:clj [clojure.test :refer [is deftest]]
               :cljs [cljs.test :as t :refer-macros [is deftest]])
            [latte-kernel.nbe :as nbe :refer :all]
            [latte-kernel.syntax :as stx]
            [latte-kernel.norm :as beta-norm]))

(deftest test-evaluation
  (is (= (evaluation 'a)
         'a))

  (is (= (evaluation '[(λ [a ✳] a) b])
         'b))

  (is (= (evaluation '[a b])
         ['a 'b]))

  (let [[b [x tx] f] (evaluation '(λ [a ✳] a))]
    (is (= b 'λ))
    (is (= x 'a))
    (is (= tx '✳))
    (is (fn? f))
    (is (= (f 5) 5)))

  (let [res (evaluation '[(λ [a ✳] (λ [b ✳] [a b])) c])
        [b [x tx] f] res]
    (is (= b 'λ))
    (is (= x 'b))
    (is (= tx '✳))
    (is (fn? f))
    (is (stx/binder? res))
    (is (= (f 'd) '[c d])))

  (let [[v1 v2] (evaluation '[[(λ [a ✳] (λ [b ✳] [a b])) c] d])]
    (is (= v1 'c))
    (is (= v2 'd)))

  (let [term '[[[(λ [x ✳] (λ [x ✳] (λ [x ✳] x))) a] b] c]]
    (is (= (evaluation term)
           'c)))

  (let [term '(::stx/ascribe z [(λ [x ✳] x) y])
        [as v1 v2] (evaluation term)]
     (is (= as ::stx/ascribe))
     (is (= v1 'z))
     (is (= v2 'y)))

  (let [term1 '(Π [⇧ A] B)
        term2 '(Π [A ✳] (Π [B ✳] (Π [⇧ (Π [⇧ A] B)] (Π [⇧ A] (Π [⇧ A] B)))))
        [b1 [v1 t1] f1] (evaluation term1)
        [b2 [v2 t2] f2] (evaluation term2)]
    (is (= b1 b2 'Π))
    (is (= t1 v2 'A))
    (is (= v1 '⇧))
    (is (= t2 '✳))
    (is (and (fn? f1) (fn? f2)))))

(deftest test-quotation
  (is (= (quotation #{'y} 'y)
         'y))

  (is (= (quotation #{'y 'z} ['y 'z])
         '[y z]))

  (is (= (quotation #{} (list 'λ '[y ✳] (fn [x] x)))
         '(λ [y ✳] y)))

  (is (= (quotation #{'y 'z} '(::stx/ascribe y z))
         '(::stx/ascribe y z)))

  (is (= (quotation #{'B} (list 'Π '[⇧ A] (fn [x] 'B)))
         '(Π [⇧ A] B))))

(deftest test-norm
  (is (= (norm 'a)
         'a))

  (is (= (norm '(λ [x ✳] x))
         '(λ [x ✳] x)))

  (is (= (norm '[[(λ [x ✳] (λ [y ✳] [x y])) z] t])
         '[z t]))

  (is (= (norm '[(λ [x ✳] (λ [y ✳] [x y])) z])
         '(λ [y ✳] [z y])))

  (is (= (norm '[[[(λ [x ✳] (λ [x ✳] (λ [x ✳] x))) a] b] c])
         'c))

  (is (= (norm '[[[(λ [x ✳] (λ [y ✳] (λ [x ✳] [x y]))) a] b] c])
         '[c b]))

  (is (= (norm '(λ [x ✳] [(λ [y ✳] (λ [z ✳] [[x y] z])) a]))
         '(λ [x ✳] (λ [z ✳] [[x a] z]))))

  (is (= (norm '[(λ [a ✳] [a b]) c])
         '[c b]))

  (is (= (norm '(Π [⇧ A] B))
         '(Π [⇧ A] B)))

  (is (= (norm '(Π [A ✳] (Π [B ✳] (Π [⇧ (Π [⇧ A] B)] (Π [⇧ A] (Π [⇧ A] B))))))
         '(Π [A ✳] (Π [B ✳] (Π [⇧ (Π [⇧ A] B)] (Π [⇧' A] (Π [⇧'' A] B)))))))

  (let [term '[(λ [y T]
                (Π [α ✳]
                 (Π [⇧ (Π [x' (Π [⇧ T] ✳)]
                        (Π [⇧' [(λ [x (Π [⇧ T] ✳)]
                                 ("prop/and" [X x] [x y]))
                                x']]
                         α))]
                  α)))
               x']]
    (is (stx/alpha-eq? (norm term)
          (first (beta-norm/beta-step term)))))

  (let [term '[(λ [a T] (Π [b T] [b a])) b]]
    (is (stx/alpha-eq? (norm term)
          (first (beta-norm/beta-step term)))))

  (let [term '(Π [a T] (Π [a T] (Π [a T] [a a])))
        res1 (stx/alpha-norm (norm term))
        res2 (stx/alpha-norm (first (beta-norm/beta-step term)))]
    (is (= res1 res2)))

  (let [term '(Π [x' T]
                [(λ [y T]
                  (Π [⇧ (Π [x' ✳] ;3
                          (Π [⇧' [(λ [x ✳]
                                    ("prop/and" [X x] [x y]))
                                  x']]
                           y))]
                     y))
                 x'])
        res1 (stx/alpha-norm (norm term))
        res2 (stx/alpha-norm (first (beta-norm/beta-step term)))]
    (is (= res1 res2)))

  (let [term '(Π [C' ✳]
               (Π [⇧ (Π [⇧' (Π [C ✳] (Π [⇧ (Π [⇧' A]
                                            (Π [⇧'' B]
                                             C))]
                                      C))]
                      (Π [⇧'' C] C'))]
                C'))
        res1 (stx/alpha-norm (norm term))
        res2 (stx/alpha-norm (first (beta-norm/beta-step term)))]
    (is (= res1 res2)))

  (let [term '("core/int")
        res1 (stx/alpha-norm (norm term))
        res2 (stx/alpha-norm (first (beta-norm/beta-step term)))]
    (is (= res1 res2))))

(deftest test-equiv-beta-red
  "These tests are the same as those in norm-test"
  (is (= (norm '[(λ [x ✳] [x x]) y])
         '[y y]))

  (is (= (norm '[(λ [x ✳] x) y])
         'y))

  (is (= (norm '[[(λ [x ✳] x) y] z])
         '[y z]))

  (is (= (norm '(λ [y [(λ [x □] x) ✳]] y))
         '(λ [y ✳] y)))

  (is (= (norm '[z [(λ [x ✳] x) y]])
         '[z y]))

  (is (= (norm '(::stx/ascribe z [(λ [x ✳] x) y]))
         '(::stx/ascribe z y)))

  (is (= (norm '(::stx/ascribe [(λ [x ✳] x) y] z))
         '(::stx/ascribe y z)))

  (is (= (norm '[x y])
         '[x y]))

  (is (= (norm '(λ [y [(λ [x □] x) ✳]] [(λ [x ✳] x) y]))
         '(λ [y ✳] y))))
