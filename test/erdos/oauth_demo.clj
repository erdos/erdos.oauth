(ns erdos.oauth-demo
  (:require [erdos.oauth]
            [org.httpkit.server]))

(def google-app-id "")
(def google-app-secret "")
(def login-url "http://localhost:8080/oauth")

(defn default-handler [request]
  {:status 200
   :headers {"Content-Type" "text/html"}
   :body (format "Click <a href=\"%s\">here</a> to log in."
                 login-url)})

(defn on-error [request response raise]
  (println request)
  (response
   {:status 200
    :body "Error during logging in!"}))

(defn on-success [request response raise]
  (println request)
  (response
   {:status 200
    :body "Success!"}))

(def wrapped-handler
  (->
   default-handler
   (erdos.oauth/wrap-oauth-google
    :url     login-url
    :id      google-app-id
    :secret  google-app-secret
    :success on-success
    :error   on-error
    :scopes  ["https://www.googleapis.com/auth/analytics.readonly"
              "https://www.googleapis.com/auth/userinfo.email"
              "https://www.googleapis.com/auth/userinfo.profile"])))

(defn main []
  (let [s (org.httpkit.server/run-server
           default-handler {:port 8080})]
    (Thread/sleep 20000)
    (s)))
;;(main)
2
