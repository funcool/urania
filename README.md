## Muse

[![Build Status](https://travis-ci.org/funcool/muse.svg?branch=master)](https://travis-ci.org/funcool/muse)

[![Clojars Project](http://clojars.org/muse/latest-version.svg)](http://clojars.org/muse)

*Muse* is a Clojure library that works hard to make your relationship with remote data simple & enjoyable. We believe that concurrent code can be elegant and efficient at the same time.

Oftentimes, your business logic relies on remote data that you need to fetch from different sources: databases, caches, web services or 3rd party APIs, and you can't mess things up. *Muse* helps you to keep your business logic clear of low-level details while performing efficiently:

* batch multiple requests to the same data source
* request data from multiple data sources concurrently
* cache previous requests

Having all this gives you the ability to access remote data sources in a concise and consistent way, while the library handles batching and overlapping requests to multiple data sources behind the scenes.

Heavily inspired by:

* [Haxl](https://github.com/facebook/Haxl) - Haskell library, Facebook, open-sourced
* [Stitch](https://www.youtube.com/watch?v=VVpmMfT8aYw) - Scala library, Twitter, not open-sourced

Talks:

* "Reinventing Haxl: Efficient, Concurrent and Concise Data Access" at EuroClojure 2015: [Video](https://goo.gl/masrsz), [Slides](https://goo.gl/h4Zuvr)

## The Idea

A core problem of many systems is balancing expressiveness against performance.

```clojure
(require '[clojure.set :refer [union intersection]])

(defn num-common-friends
  [x y]
  (count (intersection (friends-of x) (friends-of y))))
```

Here, `(friends-of x)` and `(friends-of y)` are independent, and you want it to be fetched concurrently in a single batch. Furthermore, if `x` and `y` refer to the same person, you don't want to redundantly re-fetch their friend list.

*Muse* allows your data fetches to be implicitly concurrent:

```clojure
(require '[muse.core :as muse])

(defn num-common-friends [x y]
  (muse/fmap (comp count intersection) (friends-of x) (friends-of y)))

(muse/run! (num-common-friends 1 2))
```

## Usage

*Attention! API is subject to change*

Include the following to your lein `project.clj` dependencies:

```clojure
[muse "0.4.0"]
```

All functions are located in `muse.core`:

```clojure
(require '[muse.core :as muse])
```

## Quickstart

Simple helper to emulate async request to the remote source with unpredictable response latency:

In ClojureScript:

```clojure
(require '[promesa.core :as prom])

(defn remote-req [id result]
  (prom/promise
    (fn [resolve]
      (let [wait (rand 1000)]
       (println "-->" id ".." wait)
       (js/setTimeout #(do (println "<--" id)
                           (resolve result))
                      wait)))))
```

In Clojure:

```clojure
(require '[promesa.core :as prom])

(defn remote-req [id result]
  (prom/promise
    (fn [resolve]
      (let [wait (rand 1000)]
       (println "-->" id ".." wait)
       (Thread/sleep wait)
       (println "<--" id)
       (resolve result)))))

```

Define data source (list of friends by given user id):

```clojure
(require '[muse.core :as muse])

(defrecord FriendsOf [id]
  muse/DataSource
  (fetch [_] (remote-req id (set (range id)))))

(defn friends-of [id]
  (FriendsOf. id))
```

Run simplest scenario:

```clojure
(friends-of 10)
;; => #core.FriendsOf{:id 10}

(muse/run! (friends-of 10))
;; --> 10 .. 877.3953983155727
;; <-- 10
;; => #<Promise {:status :pending}>

(deref (muse/run! (friends-of 10)))
;; --> 10 .. 412.97080768100585
;; <-- 10
;; => #{0 7 1 4 6 3 2 9 5 8}

(muse/run!! (friends-of 10)) ;; blocks until done
;; --> 10 .. 834.4564727277141
;; <-- 10
;; => #{0 7 1 4 6 3 2 9 5 8}
```

There is nothing special about it (yet), let's do something more interesting:

```clojure
(muse/fmap count (friends-of 10))
;; => #<MuseMap (clojure.core$count@1b932280 core.FriendsOf[10])>

(muse/run!! (muse/fmap count (friends-of 10)))
;; --> 10 .. 844.5086574753595
;; <-- 10
;; => 10

(muse/fmap inc (muse/fmap count (friends-of 3)))
;; => #<MuseMap (clojure.core$comp$fn__4192@4275ef0b core.FriendsOf[3])>

(muse/run!! (muse/fmap inc (muse/fmap count (friends-of 3))))
;; --> 3 .. 334.5374146247876
;; <-- 3
;; => 4
```

Let's imagine we have another data source: users' activity score by given user id.

```clojure
(defrecord ActivityScore [id]
  muse/DataSource
  (fetch [_] (remote-req id (inc id))))
```

Nested data fetches (you can see 2 levels of execution):

```clojure
(defn first-friend-activity []
  (->> (friends-of 10)
       (muse/fmap sort)
       (muse/fmap first)
       (muse/flat-map #(ActivityScore. %))))

(muse/run!! (first-friend-activity))
;; --> 10 .. 576.5833162596521
;; <-- 10
;; --> 0 .. 275.28637368204966
;; <-- 0
;; => 1
```

And now a few amazing facts.

```clojure
(require '[clojure.set :refer [union intersection]])

(defn num-common-friends [x y]
  (muse/fmap (comp count intersection) (friends-of x) (friends-of y)))
```

1) `muse` automatically runs fetches concurrently:

```clojure
(muse/run!! (num-common-friends 3 4))
;; --> 3 .. 50.56579162982433
;; --> 4 .. 247.60281831534402
;; <-- 3
;; <-- 4
;; => 3
```

2) `muse` detects duplicated requests and caches results to avoid redundant work:

```clojure
(muse/run!! (num-common-friends 5 5))
;; --> 5 .. 781.2024344113081
;; <-- 5
;; => 5
```

3) seq operations will also run concurrently:

```clojure
(defn friends-of-friends [id]
  (->> (friends-of id)
       (muse/traverse #(friends-of %))
       (muse/fmap (partial apply union))))

(muse/run!! (friends-of-friends 5))
;; --> 5 .. 972.1322804759812
;; <-- 5
;; --> 0 .. 498.6426390505534
;; --> 1 .. 136.49940971567355
;; --> 4 .. 874.777296180928
;; <-- 1
;; --> 3 .. 910.0740298270428
;; <-- 0
;; --> 2 .. 995.5441177163739
;; <-- 4
;; <-- 3
;; <-- 2
;; => #{0 1 3 2}
```

4) you can implement `BatchedSource` protocol to tell `muse` how to batch requests:

```clojure
(defrecord FriendsOf [id]
  muse/DataSource
  (fetch [_] (remote-req id (set (range id))))

  muse/BatchedSource
  (fetch-multi [_ users]
    (let [ids (cons id (map :id users))]
      (->> ids
           (map #(vector %1 (set (range %1))))
           (into {})
           (remote-req ids)))))

(muse/run!! (friends-of-friends 5))
;; --> 5 .. 783.7984574012655
;; <-- 5
;; --> (0 1 4 3 2) .. 420.575997272024
;; <-- (0 1 4 3 2)
;; => #{0 1 3 2}
```

## Misc

If you come from Haskell you will probably like shortcuts:

```clojure
(muse/<$> inc (muse/<$> count (friends-of 3)))
;; => #<MuseMap (clojure.core$comp$fn__4192@6f2c4a58 core.FriendsOf[3])>

(muse/run!! (muse/<$> inc (muse/<$> count (friends-of 3))))
;; => 4
```

Custom response cache id:

```clojure
(defrecord Timeline [username]
  muse/DataSource
  (fetch [_] (remote-req username (str username "'s timeline ")))

  muse/LabeledSource
  (resource-id [_] username))

(muse/fmap count (Timeline. "@kachayev"))
;; => #<MuseMap (clojure.core$count@1b932280 core.Timeline[@kachayev])>

(muse/run!! (muse/fmap count (Timeline. "@kachayev")))
;; --> @kachayev .. 929.3864355882571
;; <-- @kachayev
;; => 21

(muse/run!! (muse/fmap str (Timeline. "@kachayev") (Timeline. "@kachayev")))
--> @kachayev .. 809.035607308747
<-- @kachayev
"@kachayev's timeline @kachayev's timeline "
```

Thorough documentation coming soon.

## ClojureScript

`Muse` can be used from ClojureScript code with few minor differences:

* `run!!` macro isn't provided (as we don't have blocking experience)
* all data sources should implement namespaced version of `LabeledSource` protocol (return pair `[resource-name id]`)

## Cats

`MuseAST` monad is compatible with `cats` library, so you can use `mlet/return` interface as well as `fmap` & `bind` functions provided by `cats.core`:

```clojure
(require '[cats.core :as m])

(defrecord Post [id]
  muse/DataSource
  (fetch [_] (remote-req id {:id id :author-id (inc id) :title "Muse"})))

(defrecord User [id]
  muse/DataSource
  (fetch [_] (remote-req id {:id id :name "Alexey"})))

(defn get-post [id]
  (m/mlet [post (Post. id)
           user (User. (:author-id post))]
    (m/return (assoc post :author user))))

(muse/run!! (get-post 10))
;; --> 10 .. 813.5857785197163
;; <-- 10
;; --> 11 .. 449.5124897284112
;; <-- 11
;; => {:author {:id 11, :name "Alexey"}, :id 10, :author-id 11, :title "Muse"}
```

## Real-World Data Sources


HTTP calls:

```clojure
(require '[muse.core :as muse])
(require '[promesa.core :as prom])

(defn async-get [url]
  (prom/future (slurp url)))

(defrecord Gist [id]
  muse/DataSource
  (fetch [_] (async-get (str "https://gist.github.com/" id))))

(muse/run!! (muse/fmap count (Gist. "21e7fe149bc5ae0bd878")))
;; => 86085

(defn gist [id]
  (muse/fmap count (Gist. id)))

;; will fetch 2 gists concurrently
(muse/run!! (muse/fmap compare (gist "21e7fe149bc5ae0bd878") (gist "b5887f66e2985a21a466")))
;; => 1
```

For an example of the use with database queries, see a detailed example here: ["Solving the N+1 Selects Problem with Muse"](https://github.com/funcool/muse/blob/master/docs/sql.md)).


## How Does It Work?

* You define data sources that you want to work with using `DataSource` protocol (describe how `fetch` should be executed).

* You declare what do you want to do with the result of each data source fetch. Yeah, right, your data source is a functor now.

* You build an AST of all operations placing data source fetching points as leaves using `muse` low-level building blocks (`value`/`fmap`/`flat-map`) and higher-level API (`collect`/`traverse`/etc). Read more about [free monads](http://goo.gl/1ubHUa) approach.

* `muse` implicitly rebuilds AST to work with tree levels instead of separate leaves that gives ability to batch requests and run independent fetches concurrently.

* `muse/run!` is an interpreter that reduces AST level by level until the whole computation is finished (it returns a promise that you can read from).

## TODO & Ideas

- [ ] applicative functors interface
- [ ] clean up code, test coverage, better high-level API

## Known Restrictions

* requires Java 8 when used from Clojure due to its use of `java.util.concurrent.CompletableFuture`
* works with the `promesa` library only (if you use other async mechanism, like `future`s you can easily turn your code to be compatible with promises)
* assumes your operations with data sources are "side-effects free", so you don't really care about the order of fetches
* yes, you need enough memory to store the whole data fetched during a single `run!` call (in case it's impossible you should probably look into other ways to solve your problem, i.e. data stream libraries)

## License

Release under the MIT license. See LICENSE for the full license.

## Contribute

* Check for open issues or open a fresh issue to start a discussion around a feature idea or a bug.
* Fork the repository on Github & fork master to `feature-*` branch to start making your changes.
* Write a test which shows that the bug was fixed or that the feature works as expected.

or simply...

* Use it.
* Enjoy it.
* Spread the word.

## Thanks

Thanks go to Simon Marlow for creating/leading Haxl project (and talking about it). And to Facebook for open-sourcing it.
