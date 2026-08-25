(ns payroll.ui.render
  "The document shell: one page, one stylesheet, views from the table.

  ## The only namespace that knows about a design system

  Everything else in `payroll.ui` and `payroll.artifact` is hiccup in and
  hiccup out. This is where `jp-go-dds` — the workspace's base design system,
  デジタル庁デザインシステム (CLAUDE.md, owner decision 2026-08-05) — is
  required, so the views, the accessibility invariants and the printable
  payslip are all testable without one.

  ## App CSS is written against the `--hig-*` token contract

  `jp-go-dds.tokens/skin-css` redefines every `--hig-*` custom property on top
  of DADS primitives, plus the three accessibility corrections DADS needs
  (44px tap targets, `color-scheme`, safe-area). So `app-css` below writes
  `var(--hig-spacing-4)` and never a DADS primitive and never a raw hex —
  which is what let three other apps in this workspace change design system
  without touching their stylesheets.

  A token the bridge does not carry resolves to NOTHING and the declaration
  silently disappears. The five palette colours used here — red, orange,
  green, blue, gray — are all bridged. The six that are not (teal, mint,
  indigo, brown, gray2-6) are avoided deliberately.

  ## The CSS string is passed IN, not read

  `jp-go-dds.page/->page` requires the vendored `dds.css` as a string, which
  is a classpath read and therefore a host effect. It is a parameter here so
  this namespace stays pure and `.cljc`; `payroll.host.jvm` does the reading
  once at start-up.

  ## Colour is never the state

  Every rule below that sets a colour also has a text counterpart produced by
  `payroll.ui.state`, and `payroll.ui.a11y`'s `:state-colour-only` rule fails
  the build if a future edit adds a coloured element that says nothing. The
  print stylesheet exists for the same reason: a payslip printed in black and
  white must still distinguish 確定 from 未確定, and it does, because the
  distinction was never in the colour."
  (:require [clojure.string :as str]
            [jp-go-dds.page :as page]
            [jp-go-dds.tokens :as tokens]
            [payroll.ui.views :as views]))

(def app-css
  "The console's own CSS, entirely on the `--hig-*` contract.

  Grouped by what it is for, because a stylesheet whose sections are not
  named is one nobody can safely delete from."
  (str/join
   "\n"
   [;; ---- shell -------------------------------------------------------
    ".console-shell{max-width:72rem;margin:0 auto;padding:var(--hig-spacing-5)}"
    ".console-nav ul{list-style:none;display:flex;flex-wrap:wrap;gap:var(--hig-spacing-2);padding:0;margin:0 0 var(--hig-spacing-5)}"
    ".console-nav a.is-current{text-decoration:underline;font-weight:700}"
    ".skip-link{position:absolute;left:-9999px}"
    ".skip-link:focus{position:static;display:inline-block;padding:var(--hig-spacing-2)}"
    ;; A visible focus ring everywhere, because the whole console is
    ;; keyboard-operable and DADS' default ring does not reach every element
    ;; this app adds.
    "a:focus-visible,button:focus-visible,input:focus-visible,select:focus-visible,textarea:focus-visible,summary:focus-visible{outline:3px solid var(--hig-color-tint);outline-offset:2px}"

    ;; ---- typography and layout ---------------------------------------
    "h1{font-size:var(--hig-text-title1-font-size);line-height:var(--hig-text-title1-line-height);margin:0 0 var(--hig-spacing-4)}"
    "h2{font-size:var(--hig-text-title3-font-size);line-height:var(--hig-text-title3-line-height);margin:0 0 var(--hig-spacing-3)}"
    "h3{font-size:var(--hig-text-headline-font-size);margin:0 0 var(--hig-spacing-2)}"
    ".hint{font-size:var(--hig-text-footnote-font-size);color:var(--hig-color-secondary-label);margin:var(--hig-spacing-1) 0 0}"
    ".empty-note{color:var(--hig-color-secondary-label);padding:var(--hig-spacing-4);border:var(--hig-hairline) dashed var(--hig-color-separator);border-radius:var(--hig-radius-md)}"
    ".req{margin-inline-start:var(--hig-spacing-2);font-size:var(--hig-text-caption1-font-size);color:var(--hig-palette-red)}"

    ;; ---- tables ------------------------------------------------------
    ;; `display:block` + `overflow-x:auto` on the wrapper is what makes a
    ;; twelve-column reconciliation table usable on a phone without any
    ;; script. The caption stays outside the scroll area so the table's
    ;; purpose is readable before the scroll.
    "table{width:100%;border-collapse:collapse;margin:var(--hig-spacing-3) 0;font-size:var(--hig-text-callout-font-size)}"
    "caption{text-align:start;font-weight:700;padding:var(--hig-spacing-2) 0}"
    "th,td{text-align:start;padding:var(--hig-spacing-2);border-bottom:var(--hig-hairline) solid var(--hig-color-separator);vertical-align:top}"
    "th[scope=col]{background:var(--hig-color-secondary-system-grouped-background)}"
    "td.amt,th.amt{text-align:end;font-variant-numeric:tabular-nums;font-family:var(--hig-font-mono)}"
    "td.why{font-size:var(--hig-text-caption1-font-size);color:var(--hig-color-secondary-label);max-width:32rem}"
    "@media (max-width:48rem){table{display:block;overflow-x:auto}}"

    ;; ---- chips: shape and word first, colour last --------------------
    ".chip{display:inline-flex;align-items:center;gap:var(--hig-spacing-1);padding:2px var(--hig-spacing-2);border-radius:var(--hig-radius-capsule);border:var(--hig-hairline) solid currentColor;font-size:var(--hig-text-caption1-font-size);white-space:nowrap}"
    ".chip-mark{font-family:var(--hig-font-mono)}"
    ".chip-ok{color:var(--hig-palette-green)}"
    ".chip-caution{color:var(--hig-palette-orange)}"
    ".chip-warn{color:var(--hig-palette-orange);font-weight:700}"
    ".chip-stop{color:var(--hig-palette-red);font-weight:700}"
    ".chip-muted{color:var(--hig-color-secondary-label)}"

    ;; ---- rows carrying an unresolved figure ---------------------------
    ;; A left border, not a background wash: a wash reduces the contrast of
    ;; the text on top of it, and this is the text an operator most needs to
    ;; read.
    ".prov-row.prov-held th,.prov-row.prov-held td{border-inline-start:4px solid var(--hig-palette-red)}"
    ".prov-row.prov-unknown th,.prov-row.prov-unknown td{border-inline-start:4px solid var(--hig-palette-orange)}"
    ".prov-mark{margin-inline-start:var(--hig-spacing-1);font-size:var(--hig-text-caption2-font-size)}"
    ".state-word{font-weight:700}"
    ".state-why{font-size:var(--hig-text-caption1-font-size);color:var(--hig-color-secondary-label);margin:var(--hig-spacing-1) 0 0;font-weight:400;text-align:start}"

    ;; ---- panels -------------------------------------------------------
    ".tone-stop{border-inline-start:6px solid var(--hig-palette-red)}"
    ".tone-warn{border-inline-start:6px solid var(--hig-palette-orange)}"
    ".tone-caution{border-inline-start:6px solid var(--hig-palette-orange)}"
    ".tone-ok{border-inline-start:6px solid var(--hig-palette-green)}"
    ".field{margin-bottom:var(--hig-spacing-4)}"
    ".kv{display:grid;grid-template-columns:auto 1fr;gap:var(--hig-spacing-2) var(--hig-spacing-4);margin:0}"
    ".kv dt{font-weight:700;color:var(--hig-color-secondary-label)}"
    ".kv dd{margin:0}"
    ".legend{display:grid;grid-template-columns:auto 1fr;gap:var(--hig-spacing-2) var(--hig-spacing-4);margin:0}"
    ".legend dd{margin:0;font-size:var(--hig-text-footnote-font-size)}"
    ".flash{padding:var(--hig-spacing-3);border-radius:var(--hig-radius-md);margin-bottom:var(--hig-spacing-4);border:var(--hig-hairline) solid currentColor}"
    ".flash-ok{color:var(--hig-palette-green)}"
    ".flash-error{color:var(--hig-palette-red)}"

    ;; ---- print --------------------------------------------------------
    ;; The payslip is the one thing here that gets printed. Navigation and
    ;; forms are dropped; every state word survives because it was never a
    ;; colour.
    "@media print{.console-nav,form,.skip-link,.legend{display:none}"
    ".console-shell{max-width:none;padding:0}"
    ".payslip{font-size:10pt}"
    "table{page-break-inside:avoid}}"]))

(defn document
  "A full HTML document string for one view.

    {:view    the view key
     :ctx     the view's context
     :css     the vendored dds.css, read by the host
     :flash   {:kind :ok|:error :message \"…\"} or nil}

  The skip link and the `<main id=\"main\">` it points at are here rather than
  in each view: a landmark every screen needs and no screen should be able to
  forget."
  [{:keys [view ctx css flash]}]
  (page/->page
   {:title (str (:view/label (get views/by-key view) "コンソール")
                " — 給与運用コンソール")
    :description "cloud-itonami ISCO-4313 給与アクターの運用コンソール"
    :lang "ja"
    :css (or css "")
    :app-css (str tokens/skin-css "\n" app-css)}
   [:a {:href "#main" :class "skip-link"} "本文へ移動"]
   [:div {:class "console-shell"}
    [:header
     (views/nav view)]
    (when flash
      [:p {:class (str "flash flash-" (name (:kind flash)))
           :role (if (= :error (:kind flash)) "alert" "status")}
       (:message flash)])
    [:main {:id "main"}
     (views/render view ctx)]
    [:footer
     [:p {:class "hint"}
      (str "cloud-itonami ISCO-4313。この console は書類を作るが、"
           "法定様式であることを主張するものは一つも無い")]]]))

(defn payslip-document
  "A printable payslip as its own document, on the same shell and the same
  stylesheet.

  A second document, and the one exception to this console's one-document
  rule — because it is not a view, it is an ARTIFACT somebody prints and
  hands to an employee, and it must not carry the console's navigation into
  their hands. It shares the shell, so it cannot miss a design-system change
  the way a second app shell would."
  [{:keys [hiccup css]}]
  (page/->page
   {:title "給与支払明細書"
    :lang "ja"
    :css (or css "")
    :app-css (str tokens/skin-css "\n" app-css)}
   [:div {:class "console-shell"}
    [:main {:id "main"} hiccup]]))
