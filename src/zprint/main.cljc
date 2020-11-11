(ns ^:no-doc zprint.main
  (:require ;[clojure.string :as str]
   [clojure.stacktrace]
    [zprint.core :refer
     [zprint-str czprint zprint-file-str set-options! load-options!]]
    [zprint.config :refer
     [get-options get-explained-options config-and-validate-all
      select-op-options vec-str-to-str merge-deep try-to-load-string]])
  #?(:clj (:gen-class)))

;;
;; This is the root namespace to run zprint as an uberjar
;;
;; # How to use this:
;;
;; java -jar zprint-filter {<options-map-if-any>} <infile >outfile
;;

;!zprint {:vector {:wrap? false}}

(def main-help-str
  (vec-str-to-str
    [(:version (get-options))
     ""
     " zprint <options-map> <input-file >output-file"
     " zprint <switches> <input-file >output-file"
     " zprint -w intput-and-output-file(s)"
     " zprint <options-map> -w intput-and-output-file(s)"
     ""
     " Where zprint is any of:"
     ""
     (str "  " (clojure.string/replace (:version (get-options)) "-" "m-"))
     (str "  " (clojure.string/replace (:version (get-options)) "-" "l-"))
     (str "  "
          "java -jar zprint-filter-"
          (second (clojure.string/split (:version (get-options)) #"-")))
     ""
     " <options-map> is a Clojure map containing zprint options."
     "               Note that since it contains spaces, it must be"
     "               wrapped in quotes, for example:"
     "               '{:width 120}'"
     ""
     "               Use the -e switch to see the total options"
     "               map, which will show you what is configurable."
     ""
     " <switches> may be any single one of:"
     ""
     "  -d  --default      Accept no configuration input."
     "  -h  --help         Output this help text."
     "  -u  --url URL      Load options from URL."
     "      --url-only URL Load only options found from URL, ignore all others."
     "  -v  --version      Output the version of zprint."
     "  -e  --explain      Output configuration, showing where"
     "                     non-default values (if any) came from."
     "  -w  --write FILE   read, format, and write to FILE (or FILEs),"
     "                     -w *.clj is supported."
     ""
     " The -w switch is the only switch where you may also have an options map!"
     ""]))

(defn format-file
  "Take a single argument, a filename string, and format it.
  Read from the file, then rewrite the file with the formatted source.
  Return an exit-status, either 0 if successful, or 1 if there were
  problems.  In the event of problems, don't change the contents of
  the file, but output text describing the problem to stderr."
  [filename]
  (let [[exit-status in-str format-str format-stderr]
          (as-> [0 nil nil nil] running-status
            (let [[exit-status in-str format-str format-stderr] running-status]
              (try [0 (slurp filename) nil nil]
                   (catch Exception e
                     [1
                      nil
                      nil
                      (str "Failed to open file " filename " because: " e)])))
            (let [[exit-status in-str format-str format-stderr] running-status]
              (if (= exit-status 1)
                ; We are done, move on
                running-status
                (try
                  [0 nil (zprint-file-str in-str filename) nil]
                  (catch Exception e
                    [1
                     nil
                     nil
                     (str "Failed to format file: " filename " because " e)]))))
            ;
            ; See comment in -main about graalVM issues with this code
            ;
            ; Write whatever is supposed to go to stdout
            (let [[exit-status in-str format-str format-stderr] running-status]
              (if (= exit-status 1)
                ; We are done, move on
                running-status
                (try (let [^java.io.Writer w (clojure.java.io/writer filename)]
                       (.write w (str format-str))
                       (.flush w)
                       (.close w)
                       [0 nil nil nil])
                     (catch Exception e
                       [1
                        nil
                        nil
                        (str "Failed to write output file: " filename
                             " because " e)]))))
            ; Write whatever is supposed to go to stderr, if anything
            (let [[exit-status in-str format-str format-stderr] running-status]
              (when format-stderr
                (let [^java.io.Writer w (clojure.java.io/writer *err*)]
                  (.write w (str format-stderr "\n"))
                  (.flush w)))
              ; We don't change the running-status because we wrote to stderr
              running-status))]
    exit-status))

;;
;; # Main
;;

(defn -main
  "Read a file from stdin, format it, and write it to sdtout.  
  Process as fast as we can using :parallel?"
  [& args]
  ; Turn off multi-zprint locking since graalvm can't handle it, and
  ; we only do one zprint at a time here in the uberjar.
  (zprint.redef/remove-locking)
  (let [options? (and (first args)
                      (clojure.string/starts-with? (clojure.string/trim (first
                                                                          args))
                                                   "{"))
        write-switch (if options? (second args) (first args))
        write? (or (= "-w" write-switch) (= "--write" write-switch))
        files (when write? (if options? (nnext args) (next args)))
        ; Now that we have the --write/-w stuff out of the way, process
        ; switches and options, having first removed the --write/-w stuff,
        ; if any
        args (if write? (if options? (take 1 args) nil) args)
        options (first args)
        #_(prn "args:" args)
        #_(println)
        #_(prn "options?" options? "write?" write? "files:" files)
        ; Some people wanted a zprint that didn't take configuration.
        ; If you say "--default" or "-d", that is what you get.
        ; --default or -d means that you get no configuration read from
        ; $HOME/.zprintrc or anywhere else.  You get the defaults.
        ;
        ; Basic support for "-s" or "--standard" is baked in, but
        ; not turned on.
        arg-count (count args)
        version? (or (= options "--version") (= options "-v"))
        help? (or (= options "--help") (= options "-h"))
        explain? (or (= options "--explain") (= options "-e"))
        default? (or (= options "--default") (= options "-d"))
        standard? (or (= options "--standard") (= options "-s"))
        url? #?(:clj (or (= options "--url") (= options "-u"))
                :default nil)
        url-only? #?(:clj (= options "--url-only")
                     :default nil)
        valid-switch?
          (or version? help? explain? default? standard? url? url-only?)
        arg-count-ok? (cond (or version? help? explain? default? standard?)
                              (= arg-count 1)
                            (or url? url-only?) (= arg-count 2))
        [option-status exit-status option-stderr op-options]
          ; [option-status exit-status option-stderr op-options]
          ; options-status :incomplete
          ;                :complete
          ; exit-status 0 or 1 (only interesting if option-stderr non-nil)
          ; option-stderr string to output to stderr
          ; op-options are options which don't affect formatting but do
          ; affect how things operate.
          (as-> [:incomplete 0 nil {}] running-status
            ; Is this an invalid switch?
            (if (and (not (clojure.string/blank? options))
                     (not valid-switch?)
                     (clojure.string/starts-with? options "-"))
              [:complete
               1
               (str "Unrecognized switch: '" options "'" "\n" main-help-str)
               {}]
              running-status)
            ; Handle switches with extraneous data
            (let [[option-status exit-status option-stderr op-options]
                    running-status]
              (if (= option-status :complete)
                running-status
                ; Does this swtich have too much data
                (if (and valid-switch? (not arg-count-ok?))
                  [:complete
                   1
                   (str "Error processing switch '"
                        options
                        "', providing "
                        (dec arg-count)
                        " additional argument"
                        (if (= (dec arg-count) 1) "" "s")
                        " was incorrect!"
                        "\n"
                        main-help-str)
                   {}]
                  running-status)))
            ; Handle switches
            (let [[option-status exit-status option-stderr op-options]
                    running-status]
              (if (= option-status :complete)
                running-status
                (if (or version? help?)
                  [:complete
                   0
                   (cond version? (:version (get-options))
                         help? main-help-str)
                   op-options]
                  running-status)))
            ; If this is not a switch, get any operational options off
            ; of the command line
            (let [[option-status exit-status option-stderr op-options]
                    running-status]
              (if (or (= option-status :complete)
                      valid-switch?
                      (empty? options))
                running-status
                (try
                  [:incomplete 0 nil (select-op-options (read-string options))]
                  (catch Exception e
                    [:complete
                     1
                     (str "Failed to use command line operational options: '"
                          options
                          "' because: "
                          e
                          ".")
                     {}]))))
            ; Get all of the operational-options (op-options),
            ; merging in any that were on the command line
            (let [[option-status exit-status option-stderr op-options]
                    running-status]
              #_(println "pre config-and-validate-all"
                         "\noption-status:" option-status
                         "\nexit-status:" exit-status
                         "\noption-stderr:" option-stderr
                         "\nop-options:" op-options)
              (if (= option-status :complete)
                running-status
                (let [[new-map doc-map errors]
                        (config-and-validate-all nil nil op-options)
                      #_(println "post config-and-validate-all"
                                 "\nnew-map selections:" (select-op-options
                                                           new-map)
                                 "\ncolor:" (:color? (get-options))
                                 "\nerrors:" errors)
                      new-map (select-op-options (merge-deep new-map
                                                             op-options))]
                  #_(println "post merge"
                             "\new-map:" new-map
                             "\ncolor:" (:color? (get-options))
                             "\nerrors:" errors)
                  (if errors
                    [:complete 1 errors nil]
                    [:incomplete 0 nil (select-op-options new-map)]))))
            ; We now have the op-options, so process -e to explain what
            ; we have for a configuration from the various command files.
            ; This won't include any command-line options since we either
            ; do switches or command-line options.
            (let [[option-status exit-status option-stderr op-options]
                    running-status]
              (if (= option-status :complete)
                running-status
                (if explain?
                  ; Force set-options to configure using op-options
                  (do (set-options! {} "" op-options)
                      [:complete
                       0
                       (zprint-str (get-explained-options))
                       op-options])
                  running-status)))
            ; If --url try to load the args - along with other args
            (let [[option-status exit-status option-stderr op-options]
                    running-status]
              (if (= option-status :complete)
                running-status
                (if url?
                  #?(:clj (try (load-options! op-options (second args))
                               [:complete 0 nil op-options]
                               (catch Exception e
                                 [:complete 1 (str e) op-options]))
                     :default running-status)
                  running-status)))
            ; If --url-only try to load the args - with no other options
            (let [[option-status exit-status option-stderr op-options]
                    running-status]
              (if (= option-status :complete)
                running-status
                (if url-only?
                  #?(:clj (try (set-options! {:configured? true})
                               (load-options! op-options (second args))
                               [:complete 0 nil op-options]
                               (catch Exception e
                                 [:complete 1 (str e) op-options]))
                     :default running-status)
                  running-status)))
            ; if --default or --standard just use what we have, nothing else
            (let [[option-status exit-status option-stderr op-options]
                    running-status]
              (if (= option-status :complete)
                running-status
                (if (or default? standard?)
                  (do (cond default? (set-options! {:configured? true})
                            standard? (set-options! {:configured? true,
                                                     #_:style,
                                                     #_:standard}))
                      [:complete 0 nil op-options])
                  [:incomplete 0 nil op-options])))
            ; Configure any command line options.  If we get here, that
            ; is all that is left to do -- all switches have been handled
            ; at this point and we would be complete by now.
            (let [[option-status exit-status option-stderr op-options]
                    running-status]
              (if (or (= option-status :complete) (empty? options))
                running-status
                ; Accept fns from command-line options map
                (try (set-options! (try-to-load-string options)
                                   "command-line options"
                                   op-options)
                     [:complete 0 nil op-options]
                     (catch Exception e
                       [:complete
                        1
                        (str "Failed to use command line options: '"
                             options
                             "' because: "
                             e
                             ".")
                        {}]))))
            ; We could nitialize using the op-options if we haven't done
            ; so already, but if we didn't have any command line options, then
            ; there are no op-options that matter.
          )]
    #_(prn "option-status" option-status
           "exit-status" exit-status
           "option-stderr" option-stderr
           "op-options" op-options)
    ; If option-stderr has something in it, we have either had some
    ; kind of a problem or we have processed the switch and it has
    ; output.  In either case, if option-stderr is non-nil we need
    ; to exit, and the exit status will be used.  Conversely, if
    ; option-stderr is nil, the exit status has no meaning.
    (if option-stderr
      (do (let [^java.io.Writer w (clojure.java.io/writer *err*)]
            (.write w (str option-stderr "\n"))
            (.flush w))
          (shutdown-agents)
          (System/exit exit-status))
      ; Do whatever formatting we should do
      (if write?
        ; We have one or more filenames to deal with
        (let [total-exit-status (reduce #(+ %1 (format-file %2)) 0 files)]
          #_(prn "total-exit-status:" total-exit-status)
          (shutdown-agents)
          (System/exit (if (> total-exit-status 0) 1 0)))
        ; We are operating on stdin and stdout
        (let [in-str (slurp *in*)
              [format-status stdout-str format-stderr]
                (try [0 (zprint-file-str in-str "<stdin>") nil]
                     (catch Exception e
                       [1 in-str (str "Failed to zprint: "
                                   (with-out-str (clojure.stacktrace/print-cause-trace e 10)))]))]
          ;
          ; We used to do this: (spit *out* fmt-str) and it worked fine
          ; in the uberjar, presumably because the JVM eats any errors on
          ; close when it is exiting.  But when using clj, spit will close
          ; stdout, and when clj closes stdout there is an error and it will
          ; bubble up to the top level.
          ;
          ; Now, we write and flush explicitly, sidestepping that particular
          ; problem. In part because there are plenty of folks that say that
          ; closing stdout is a bad idea.
          ;
          ; Getting this to work with graalvm was a pain.  In particular,
          ; w/out the (str ...) around fmt-str, graalvm would error out
          ; with an "unable to find a write function" error.  You could
          ; get around this by offering graalvm a reflectconfig file, but
          ; that is just one more file that someone needs to have to be
          ; able to make this work.  You could also get around this (probably)
          ; by type hinting fmt-str, though I didn't try that.
          ;
          ; Write whatever is supposed to go to stdout
          (let [^java.io.Writer w (clojure.java.io/writer *out*)]
            (.write w (str stdout-str))
            (.flush w))
          ; Write whatever is supposed to go to stderr, if any
          (when format-stderr
            (let [^java.io.Writer w (clojure.java.io/writer *err*)]
              (.write w (str format-stderr "\n"))
              (.flush w)))
          ; Since we did :parallel? we need to shut down the pmap threadpool
          ; so the process will end!
          (shutdown-agents)
          (System/exit format-status))))))

