(ns erdos.oauth-test
  (:require [clojure.test :refer :all]
            [erdos.oauth :refer :all :as eo]))

(deftest do-callback-test
  (testing "Async success"
    (let [state (promise)
          response-fn #(deliver state [:response %])
          raise-fn    #(deliver state [:raise %])
          callback (fn [req success raise]
                     (is (= ::input req))
                     (success ::resp))]
      (@#'eo/do-callback ::input true response-fn raise-fn callback)
      (is (= [:response ::resp] @state))))
  (testing "Async error"
    (let [state (promise)
          response-fn #(deliver state [:response %])
          raise-fn    #(deliver state [:raise %])
          callback (fn [req success raise]
                     (is (= ::input req))
                     (raise ::resp))]
      (@#'eo/do-callback ::input true response-fn raise-fn callback)
      (is (= [:raise ::resp] @state))))
  (testing "Sync success"
    (let [state (promise)
          response-fn #(deliver state [:response %])
          raise-fn    #(deliver state [:raise %])
          callback (fn [req]
                     (is (= ::input req))
                     ::resp)]
      (@#'eo/do-callback ::input false response-fn raise-fn callback)
      (is (= [:response ::resp] @state))))
  (testing "Sync error"
    (let [state (promise)
          response-fn #(deliver state [:response %])
          raise-fn    #(deliver state [:raise %])
          callback (fn [req]
                     (is (= ::input req))
                     (throw (ex-info "example err" {:error 1})))]
      (@#'eo/do-callback ::input false response-fn raise-fn callback)
      (is (and (vector? @state)
               (= :raise (first @state))
               (= {:error 1} (ex-data (second @state))))))))

(deftest query-string->map-test
  (testing "empty query string"
    (is (nil? (@#'eo/query-string->map nil)))
    (is (nil? (@#'eo/query-string->map "")))
    (is (nil? (@#'eo/query-string->map "alabama"))))

  (testing "simple examples"
    (is (= {"aaa" "bbb"} (@#'eo/query-string->map "aaa=bbb")))
    (is (= {"a1" "a" "b2" "bb"} (@#'eo/query-string->map "a1=a&b2=bb"))))
  (testing "Url decoded?"
    (is (= {"one key" "one value"} (@#'eo/query-string->map "one+key=one+value")))))

(deftest build-url-test
  (testing "No args map"
    (is (= "url" (@#'eo/build-url "url" {})))
    (is (= "url" (@#'eo/build-url "url" nil))))

  (testing "Simple cases"
    (is (= "url?a=1" (@#'eo/build-url "url" {"a" 1}))))
  (testing "Many types"
    (is (= "url?a=1&b=b" (@#'eo/build-url "url" {:a 1 :b 'b})))))
