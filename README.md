# erdos.oauth

A ring wrapper in Clojure for OAuth support.

## Note

This is a Work-In-Progress support. Examples are expected to run but many (undocumented) features are subject to change.


## Usage

### See the [example](test/erdos/oauth_demo.clj) for a demo application!

1. Create an app id and secret on the admin console of the selected oauth provider.
2. Create success and error callback functions as below.
3. Transform the handler function with a configured oauth wrapper.
4. Redirect the user to the url given in the `:url` key to start the authentication process.
5. You can use multiple oauth wrapper functions at the same time to support many different OAuth providers. Please note that you need to use different `:url` values for each of them.

``` clojure

(def on-success [request response raise]
    (println (:oauth-success request))
    (response {:status 200 :body "OK"}))

(def on-error [request response raise]
    (println (:oauth-error request))
    (response {:status 500 :body "error!"}))

(def handler
  (->
    original-handler
    (oauth/wrap-oauth-google
       :url     "http://localhost:80/oauth"
       :id      "MY-ANALYTICS-ID"
       :secret  "MY-ANALYTICS-SECRET"
       :success on-success
       :error   on-error
       :scopes   ["https://www.googleapis.com/auth/analytics.readonly"
                  "https://www.googleapis.com/auth/userinfo.email"
                  "https://www.googleapis.com/auth/userinfo.profile"])))

```

## License

Copyright © 2017 FIXME

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
