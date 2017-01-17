(ns erdos.oauth-demo
  (:require [erdos.oauth]
            [org.httpkit.server]))

;; these values should be according to your app settings
(def google-app-id "")
(def google-app-secret "")
(def login-url "http://localhost:8000/oauth/analytics")


(defn default-handler [request]
  {:status 200
   :headers {"Content-Type" "text/html"}
   :body (format "Click <a href=\"%s\">here</a> to log in."
                 login-url)})

(defn on-error [request response raise]
  (response
   {:status 200
    :body (str "Error during logging in: " (:oauth-error request))}))

(defn on-success [request response raise]
  ;; we should save the user id to session variables and redirect
  (response
   {:status 200
    :body (str "Success! You are: "
               (-> request :oauth-success :user-info :name)
               " with id "
               (-> request :oauth-success :user-info :id))}))

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
           wrapped-handler {:port 8000})]
    (Thread/sleep 20000)
    (s)))

;;(main)
