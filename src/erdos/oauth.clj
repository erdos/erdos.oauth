(ns erdos.oauth
  "OAuth client wrapper for Ring."
  (:require
   [org.httpkit.client :as client]
   [cheshire.core :as json]))


(defn request->url [req]
  (str (-> req :scheme name) "://"
       (:server-name req)
       (if-let [p (:server-port req)] (str ":" p))
       (:uri req)))


(defn build-url [url opts]
  ;; Old version:
  ;;      (str url "?" (clojure.string/join "&" (for [[k v] opts] (str (name k) "=" (name v)))))
  ;; New is 3-5x faster with imperative style!
  (let [url (str url)
        b   (new StringBuilder url)]
    (reduce-kv
     (fn [_ k v]
       (.append b \&)
       (.append b (name k))
       (.append b \=)
       (.append b (name v)))
     nil opts)
    (.setCharAt b (.length url) \?)
    (.toString b)))


(defn- redirect-to
  ([s] {:status 302, :headers {"Location" (str s)}})
  ([status s] {:status status, :headers {"Location" (str s)}}))


(defn handle-oauth
  "Arguments:
  First argument is request map. Second argument is map of keys:
  - :url-endpoint - OAuth 2.0 endpoint of provider
  - :url-exchange - Code to token exchange endpoint of provider
  - :id           - Client id
  - :secret       - Client secret
  - :url          - Local url for login
  - :success      - Asyncronous ring handler fn called on success.
  - :error        - Asyncronous ring handler fn called on error."
  [request response raise {:as opts :keys [error success]}]
  (assert (= (request->url request) (:url opts)))
  (assert (string? (:url-endpoint opts)))
  (assert (string? (:url-exchange opts)))
  (assert (string? (:id opts)))
  (assert (string? (:secret opts)))
  (assert (string? (:url opts)))
  (assert (fn? (:success opts)))
  (assert (fn? (:error opts)))
  ;; (println request)
  (cond
    ;; error during login:
    (-> request :query-params (get "error"))
    (error (assoc request :oauth-error (-> request :query-params (get "error")))
           response
           raise)
    ;; successfully redirected back:
    (-> request :query-params (get "code"))
    (let [code (-> request :query-params (get "code"))
          m {"grant_type"    "authorization_code"
             "code"          code
             "redirect_uri"  (:url opts)
             "client_id"     (:id opts)
             "client_secret" (:secret opts)}]
      (client/post (:url-exchange opts)
                   {:form-params m}
                   (fn [resp]
                     (try
                       (if-let [err (:error resp)]
                         ;; code is maybe expired, etc.
                         (error (assoc request :oauth-error err)
                                response
                                raise)
                         (let [obj (-> resp :body json/parse-string)
                               m   {:access-token  (get obj "access_token"),
                                    :expires-in    (get obj "expires_in"),
                                    :token-type    (get obj "token_type")
                                    ;; always Bearer!
                                    :refresh-token (get obj "refresh_token")}]
                           (success (assoc request :oauth-success m)
                                    response
                                    raise)))
                       (catch Throwable t (raise t))))))
    ;; redirect to provider:
    :default
    (let [m {:client_id     (:id opts)
             :redirect_uri  (:url opts)
             :response_type :code}
          m (into m (:endpoint-params opts))]
      (response (redirect-to 307 (build-url (:url-endpoint opts) m))))))


(defn- ->handler-fn
  "Creates an error/success handler fn from object."
  [x]
  (assert (some? x) "Function not given!")
  (cond
   (fn? x) x
   (map? x) (fn [req resp err] (resp x))
   (instance? java.net.URL x)
   (fn [req resp err] (resp (redirect-to x)))
   (instance? java.net.URI x)
   (fn [req resp err] (resp (redirect-to x)))
   :else
   (throw (IllegalArgumentException.
           (str "Can not create fn from " (type x))))))


(defmulti wrap-oauth #(some-> %2 :service name .toLowerCase))


;; TODO: error/succes fv mukodese is sync/async kene legyen.
(defmethod wrap-oauth nil
  #_
  "For options: see handle-oauth function.
   - :error/:success can be url (for redirecting) or string (for display) or map (to return) too."
  [handler & {:as opts :keys [id secret url]}]
  (assert (:error opts))
  (assert (:success opts))
  (let [opts (-> opts
                 (update :error ->handler-fn)
                 (update :success ->handler-fn))]
    (fn
      ([request]
       (let [p (promise)]
         (if (= (request->url request) url)
           (handle-oauth request (partial deliver p) (partial deliver p) opts)
           (deliver p (handler request)))
         (when (instance? Throwable @p) (throw @p) @p)))
      ([request response raise]
       (if (= (request->url request) url)
         (handle-oauth request response raise opts)
         (handler request response raise))))))


(defn google-token->userinfo
  ([token]
   (let [p (promise)]
     (google-token->userinfo token (partial deliver p))
     p))
  ([token callback-fn]
   (let [url (str "https://www.googleapis.com/oauth2/v1/userinfo")
         m {"Authorization" (str "Bearer " token)}]
     (client/get url {:headers m}
                 #(let [m (json/parse-string (:body %))]
                    (callback-fn (assoc m
                                        :id (get m "id")
                                        :name (get m "name"))))))))


(defn facebook-token->userinfo
  ([token]
   (let [p (promise)]
     (facebook-token->userinfo token (partial deliver p))
     p))
  ([token callback-fn]
   (let [url "https://graph.facebook.com/me"
         m  {:access_token token}]
     (client/get url {:query-params m}
                 #(let [m (json/parse-string (:body %))]
                    (callback-fn (assoc m
                                        :id (get m "id")
                                        :name (get m "name"))))))))


(defn facebook-success-handler-wrapper [handler]
  (fn
    ([request]
     (->> request :oauth-success :access-token
          (facebook-token->userinfo)
          (assoc-in request [:oauth-success :user-info])
          (handler)))
    ([request response raise]
     (facebook-token->userinfo
      (-> request :oauth-success :access-token)
      (fn [user-info]
        (handler (assoc-in request [:oauth-success :user-info] user-info)
                 response
                 raise))))))


;; TODO: Test this.
;; TODO: add refresh_token and expires parameters to success fun.
(defmethod wrap-oauth "google"
  #_
  "See:

  - https://console.developers.google.com/?pli=1
  - https://developers.google.com/youtube/v3/guides/auth/server-side-web-apps#Obtaining_Access_Tokens"
  [handler & {:keys [url success error id secret scopes]}]
  (assert (coll? scopes) "No :scopes given.")
  (wrap-oauth
   handler
   :url url
   :id id
   :secret secret
   :url-endpoint "https://accounts.google.com/o/oauth2/auth"
   :url-exchange "https://accounts.google.com/o/oauth2/token" ;; ezzel mi van.
   :success (facebook-success-handler-wrapper success)
   :error error
   :endpoint-params {:scope (clojure.string/join " " scopes)
                     :access_type "offline"}))


(defmethod wrap-oauth "facebook"
  [handler & {:keys [url success error id secret scopes]}]
  (assert (coll? scopes) "No :scopes given.")
  (let [scopes (clojure.string/join " " scopes)]
    (wrap-oauth
     handler
     :url url
     :id id
     :secret secret
     :url-endpoint "https://www.facebook.com/dialog/oauth"
     :url-exchange "https://graph.facebook.com/v2.3/oauth/access_token"
     :success success
     :error error
     :endpoint-params {:scope scopes :response_type "code"})))


(defmethod wrap-oauth "linkedin"
  #_
  "Wrapper for LinkedIn authentication. For configuration, see:
  - https://www.linkedin.com/secure/developer
  - https://developer.linkedin.com/docs/oauth2"
  [handler & {:keys [url success error id secret]}]
  (let []
    (wrap-oauth
     handler
     :url          url
     :id           id
     :secret       secret
     :url-endpoint "https://www.linkedin.com/oauth/v2/authorization"
     :url-exchange "https://www.linkedin.com/oauth/v2/accessToken"
     :success      success
     :error        error)))


(defmacro defwrapper [m]
  `(defn ~(symbol (str "wrap-oauth-" (name m))) [h# & opts#]
     (apply wrap-oauth :service ~m opts#)))


(defwrapper :facebook)
(defwrapper :google)
(defwrapper :linkedin)


:OK
