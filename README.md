# com.7theta/via-schema
[![Current Version](https://img.shields.io/clojars/v/com.7theta/via-schema.svg)](https://clojars.org/com.7theta/via-schema)
[![GitHub license](https://img.shields.io/github/license/7theta/via-schema.svg)](LICENSE)
[![Dependencies Status](https://jarkeeper.com/7theta/via-schema/status.svg)](https://jarkeeper.com/7theta/via-schema)

Malli schema validation, input and output scrubbing for [via](https://github.com/7theta/via) events and subs.

## Usage

The `>fn` macro in `via-schema.core` allows you to create an enhanced
version of a clojure function that is appropriate for use with both
`signum.subs/reg-sub` and `via.events/reg-sub-via`.

These functions work almost like any normal Clojure function, except
inputs and output are both validated when a schema is provided.

```clojure
(:require [via-schema.core :refer [>fn]])

(def add
  (>fn
   [a b]
   [number? number? => number?]
   (+ a b)))

(add 1 2) ;; => 3
```

You can easily compose the resulting function with a `signum sub`,
or with a `via event`.

```clojure
(:require [signum.subs :refer [reg-sub]])

(reg-sub
 :your.via.sub/add
 (>fn
  [[_ a b]
   [[:tuple _ number? number?] => number?]]
  (+ a b)))
```

When writing a `via event`, `via-schema` will try to do the right
thing when it comes to applying the schema to the `:via/reply` field
on an event response. 

```clojure
(:require [via.events :refer [reg-event-via]])

(reg-event-via
 :your.via.event/add
 (>fn
  [_ [_ a b]]
  [_ [:tuple _ number? number?] => [:map [:result number?]]]
  {:via/status 200
   :via/reply {:result (+ a b)}}))
```

Additionally, by default via will scrub unrecognized keys from inputs
and outputs, and attempt to coerce inputs and outputs to match their
schemas. This option can be enabled/disabled by setting
`:via-schema.core/coerce` to `true` or `false` in the function meta
map.

Most other options and syntax from `https://github.com/teknql/aave`
should work.

## Copyright and License

Copyright © 2021 7theta

Distributed under the Eclipse Public License.
