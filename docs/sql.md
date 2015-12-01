# Solving the "N+1 Selects Problem" with Muse

The so-called [N+1 selects problem](http://ocharles.org.uk/blog/posts/2014-03-24-queries-in-loops-without-a-care-in-the-world.html) is characterized by a set of queries in a loop.

Given that we have users and posts with authors, following the following datascript schema:

```clojure
(def schema
  {:user {:user/id {:db/cardinality :db.cardinality/one}
          :user/name {:db/cardinality :db.cardinality/one}}
   :post {:post/id {:db/cardinality :db.cardinality/one}
          :post/user {:db/cardinality :db.cardinality/one}
          :post/title {:db/cardinality :db.cardinality/one}
          :post/text {:db/cardinality :db.cardinality/one}}})
```

We want to fetch the last `n` posts and, for each one, all of its author data. The most generic version of this code would perform one data fetch for `get-posts`, then another for each call to `get-user`; assuming each one is implemented as a query, that means **N+1 selects**.

```clojure
(defn attach-author [{user :post/user :as post}]
  (assoc post :post/user (get-user user)))

(defn latest-posts [n]
  (map attach-author (get-posts n)))
```


Using similar code, the Muse implementation will perform exactly two data fetches: one to `get-posts` and one with all the `get-user` calls batched together.

## The Data Sources

First of all, import muse:

```clojure
(require '[muse.core :as muse])
```

Let's create a datascript database and populate it with a few users and posts:

```clojure
(require '[datascript.core :as d])

(def db (d/create-conn schema))

;; add users
(d/transact! db [{:user/name "Ada"
                  :user/id 1}
                 {:user/name "Bob"
                  :user/id 2}
                 {:user/name "Claire"
                  :user/id 3}
                 {:user/name "Diana"
                  :user/id 4}
                 {:user/name "Edsger"
                  :user/id 5}
                 {:user/name "Frank"
                  :user/id 6}])

;; add posts
(doseq [user-id [1 2 3 4 5 6]
        post-id (take 10 (iterate inc (* 100 user-id)))]
  (d/transact! db [{:post/id post-id
                    :post/user user-id
                    :post/title (str  "A post with id #" post-id)
                    :post/text ""}]))
```

Define `Posts` data source as a record that implements two protocols: `muse/DataSource` and `muse/LabeledSource`.

`DataSource` defines the mechanism for fetching data from a remote source. The `fetch` function should return a promise and here we're using `promise` function from `promesa`, available in Clojure and ClojureScript.

`LabeledSource` defines the data source's identifier to be used as a key in requests cache.

```clojure
(require '[promesa.core :as prom])

(def posts-query
  '[:find [?e ...]
    :where [?e :post/id ?id]])

(defn get-post-ids [limit]
  (take limit (d/q posts-query @db)))

(defn pull-posts [limit]
  (d/pull-many @db '[*] (get-post-ids limit)))

(defrecord Posts [limit]
  muse/DataSource
  (fetch [_]
    (prom/promise
     (fn [resolve reject]
      (println "Fetching " limit " post(s)")
      (resolve (pull-posts limit)))))

  muse/LabeledSource
  (resource-id [_] limit))

(defn get-posts [limit]
  (Posts. limit))
```

Define `User` data source that additionally implements the `muse/BatchedSource` protocol.

`BatchedSource` protocol's `fetch-multi` function should return a promise as well as `fetch`. The difference is that `muse` assumes to read from this promise a mapping from id to individual fetch result. `Muse` library will automatically figure out all cases when it's possible to batch multiple individual requests into a single one.

```clojure
(defn user-query [id]
  `[:find [?e]
    :where [?e :user/id ~id]])

(def users-query
  '[:find [?e ...]
    :in $ ?wanted
    :where [?e :user/id ?id]
           [(?wanted ?id)]])

(defn get-user-by-id [id]
  (first (d/q (user-query id) @db)))

(defn pull-user [id]
  (d/pull @db '[*] (get-user-by-id id)))

(defn pull-users [ids]
  (d/pull-many @db
               '[*]
               (d/q users-query @db (set ids))))

(defrecord User [id]
  muse/DataSource
  (fetch [_]
    (prom/promise
     (fn [resolve reject]
       (println "Fetching User #" id)
       (resolve (pull-user id)))))

  muse/BatchedSource
  (fetch-multi [_ users]
    (prom/promise
     (fn [resolve reject]
       (let [all-ids (into #{id} (map :id users))
             users (pull-users all-ids)
             ids (map :user/id users)]
         (println "Fetching Users " all-ids)
         (resolve (zipmap ids users)))))))

(defn get-user [id]
  (User. id))
```

## Tying it All Together

All that remains to make the original example work is to define `get-posts` and `get-user` helpers.

The naïve code that looks like it will do N+1 fetches will now do just two.

```clojure
(defn attach-author [{user :post/user :as post}]
  (muse/fmap #(assoc post :post/user %)
             (get-user user)))

(defn latest-posts [n]
  (muse/traverse attach-author
                 (get-posts n)))
```

The only change is that we have to place a call to `muse/run!`  interpreter (or its blocking version `muse/run!!`):

```clojure
(muse/run!! (latest-posts 10))
;; Fetching  10  post(s)
;; Fetching Users  #{1 4 6 5}
```

Note that muse detecst and eliminate duplicate user requests:

```clojure
(muse/run!! (muse/collect [(get-user 1) (get-user 1) (get-user 2)]))
;; Fetching Users  #{1 2}
```
