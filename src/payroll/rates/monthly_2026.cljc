;; -*- GENERATED FILE — DO NOT EDIT BY HAND -*-
;;
;; Produced by `clojure -M:importer` (tools/import_nta_2026.clj) from the
;; 国税庁 workbook pinned by the SHA-256 in `provenance` below. Every figure
;; here was READ out of that workbook; none was typed. To change it, change
;; the importer or the pin and regenerate — an edit made here is an edit the
;; next regeneration silently reverts.

(ns payroll.rates.monthly-2026
  "令和8年分 給与所得の源泉徴収税額表（月額表）as data. GENERATED — see the
  header comment; do not edit by hand.

  Pure data and no logic, so that the table can be diffed against the
  printed workbook without reading any code, and so that a regeneration
  that changes a figure shows up as a changed figure rather than as a
  changed calculation. The reading of it is `payroll.rates`.

  The 月額表 is one of three tables in the 2026 publication. 日額表 and
  賞与に対する源泉徴収税額の算出率の表 are NOT here, and `payroll.rates`
  refuses rather than approximating them from this one.")

;; ---------------------------------------------------------------------------
;; Provenance
;; ---------------------------------------------------------------------------

(def provenance
  "Which bytes this table came out of, and what was done to them.

  `:source/sha256` is the load-bearing field. A URL says where a file was
  fetched from, not what was fetched: the 国税庁 replaces these workbooks in
  place when a 告示 is amended, so the digest is the only thing that lets a
  reader confirm that the figures below came from the edition they are
  holding.

  `:transform/*` records the run rather than the source, so that a parse
  that read fewer rows than the sheet has is visible in the data instead of
  only in the importer's console output."
  {:source/url "https://www.nta.go.jp/publication/pamph/gensen/zeigakuhyo2026/data/01-07.xls", :source/page "https://www.nta.go.jp/publication/pamph/gensen/zeigakuhyo2026/01.htm", :source/title "令和8年分 給与所得の源泉徴収税額表（月額表）", :source/authority "国税庁", :source/sha256 "50aafa072df1bb6b6aa253a021f7cc246265c3f2393f9988ee01ad121bc4f310", :source/bytes 81408, :source/retrieved-at "2026-08-26", :source/sheet "月額表", :source/applicability {:applicability/from "2026-01", :applicability/to "2026-12", :applicability/basis "令和8年分。表題行から読み取った適用年であり、importer が仮定した年ではない", :applicability/not-covered "日額表・賞与に対する源泉徴収税額の算出率の表はこの workbook の別表であり、転記していない"}, :transform/importer "tools/import_nta_2026.clj", :transform/version 1, :transform/sheets 1, :transform/rows-read 413, :transform/bands 231, :transform/thresholds 9, :transform/kou-segments 9, :transform/otsu-segments 2})

;; ---------------------------------------------------------------------------
;; 105,000円未満 — the band below the table
;; ---------------------------------------------------------------------------

(def sub-minimum
  "The rows below the table's floor.

  甲 is zero for every dependant count, which is an ANSWER and not an
  absence. 乙 is the only place in the 月額表 where the workbook prints a
  RATE instead of an amount, and it is kept as an exact ratio: the tax is
  3.063% of the amount, and what the workbook does not say is how to
  resolve the fraction of a yen that produces — which is why
  `payroll.rates/withhold` refuses 乙 here rather than picking a rule."
  {:band/from 0, :band/to 105000, :band/kou [0 0 0 0 0 0 0 0], :band/otsu-rate 3063/100000, :band/otsu-basis "その月の社会保険料等控除後の給与等の金額の3.063％に相当する金額"})

;; ---------------------------------------------------------------------------
;; The 231 discrete bands
;; ---------------------------------------------------------------------------

(def bands
  "105,000円以上 740,000円未満, in 231 contiguous bands.

  `:band/from` is 以上 (inclusive) and `:band/to` is 未満 (exclusive), which
  is the workbook's own convention and the reason each band's `:band/to`
  equals the next one's `:band/from` rather than being one yen below it.

  `:band/kou` is indexed by 扶養親族等の数, 0 through 7. Above seven the
  workbook subtracts `dependants-beyond-7-deduction` per person from the
  7人 figure; that arithmetic is in `payroll.rates` and not baked in here,
  because it is a rule and this file holds only what was printed."
  [{:band/no 1, :band/from 105000, :band/to 107000, :band/kou [170 0 0 0 0 0 0 0], :band/otsu 3800}
   {:band/no 2, :band/from 107000, :band/to 109000, :band/kou [280 0 0 0 0 0 0 0], :band/otsu 3800}
   {:band/no 3, :band/from 109000, :band/to 111000, :band/kou [380 0 0 0 0 0 0 0], :band/otsu 3900}
   {:band/no 4, :band/from 111000, :band/to 113000, :band/kou [480 0 0 0 0 0 0 0], :band/otsu 4000}
   {:band/no 5, :band/from 113000, :band/to 115000, :band/kou [580 0 0 0 0 0 0 0], :band/otsu 4100}
   {:band/no 6, :band/from 115000, :band/to 117000, :band/kou [680 0 0 0 0 0 0 0], :band/otsu 4100}
   {:band/no 7, :band/from 117000, :band/to 119000, :band/kou [790 0 0 0 0 0 0 0], :band/otsu 4200}
   {:band/no 8, :band/from 119000, :band/to 121000, :band/kou [890 0 0 0 0 0 0 0], :band/otsu 4300}
   {:band/no 9, :band/from 121000, :band/to 123000, :band/kou [990 0 0 0 0 0 0 0], :band/otsu 4300}
   {:band/no 10, :band/from 123000, :band/to 125000, :band/kou [1090 0 0 0 0 0 0 0], :band/otsu 4400}
   {:band/no 11, :band/from 125000, :band/to 127000, :band/kou [1190 0 0 0 0 0 0 0], :band/otsu 4700}
   {:band/no 12, :band/from 127000, :band/to 129000, :band/kou [1300 0 0 0 0 0 0 0], :band/otsu 5000}
   {:band/no 13, :band/from 129000, :band/to 131000, :band/kou [1400 0 0 0 0 0 0 0], :band/otsu 5300}
   {:band/no 14, :band/from 131000, :band/to 133000, :band/kou [1500 0 0 0 0 0 0 0], :band/otsu 5500}
   {:band/no 15, :band/from 133000, :band/to 135000, :band/kou [1600 0 0 0 0 0 0 0], :band/otsu 5800}
   {:band/no 16, :band/from 135000, :band/to 137000, :band/kou [1710 0 0 0 0 0 0 0], :band/otsu 6100}
   {:band/no 17, :band/from 137000, :band/to 139000, :band/kou [1810 190 0 0 0 0 0 0], :band/otsu 6400}
   {:band/no 18, :band/from 139000, :band/to 141000, :band/kou [1910 300 0 0 0 0 0 0], :band/otsu 6700}
   {:band/no 19, :band/from 141000, :band/to 143000, :band/kou [2010 400 0 0 0 0 0 0], :band/otsu 7000}
   {:band/no 20, :band/from 143000, :band/to 145000, :band/kou [2110 500 0 0 0 0 0 0], :band/otsu 7400}
   {:band/no 21, :band/from 145000, :band/to 147000, :band/kou [2220 600 0 0 0 0 0 0], :band/otsu 7700}
   {:band/no 22, :band/from 147000, :band/to 149000, :band/kou [2320 700 0 0 0 0 0 0], :band/otsu 8000}
   {:band/no 23, :band/from 149000, :band/to 151000, :band/kou [2420 810 0 0 0 0 0 0], :band/otsu 8300}
   {:band/no 24, :band/from 151000, :band/to 153000, :band/kou [2520 910 0 0 0 0 0 0], :band/otsu 8600}
   {:band/no 25, :band/from 153000, :band/to 155000, :band/kou [2620 1010 0 0 0 0 0 0], :band/otsu 8900}
   {:band/no 26, :band/from 155000, :band/to 157000, :band/kou [2730 1110 0 0 0 0 0 0], :band/otsu 9200}
   {:band/no 27, :band/from 157000, :band/to 159000, :band/kou [2830 1210 0 0 0 0 0 0], :band/otsu 9500}
   {:band/no 28, :band/from 159000, :band/to 161000, :band/kou [2910 1300 0 0 0 0 0 0], :band/otsu 9800}
   {:band/no 29, :band/from 161000, :band/to 163000, :band/kou [2980 1370 0 0 0 0 0 0], :band/otsu 10100}
   {:band/no 30, :band/from 163000, :band/to 165000, :band/kou [3050 1440 0 0 0 0 0 0], :band/otsu 10400}
   {:band/no 31, :band/from 165000, :band/to 167000, :band/kou [3120 1510 0 0 0 0 0 0], :band/otsu 10700}
   {:band/no 32, :band/from 167000, :band/to 169000, :band/kou [3200 1580 0 0 0 0 0 0], :band/otsu 11000}
   {:band/no 33, :band/from 169000, :band/to 171000, :band/kou [3270 1650 0 0 0 0 0 0], :band/otsu 11300}
   {:band/no 34, :band/from 171000, :band/to 173000, :band/kou [3340 1730 100 0 0 0 0 0], :band/otsu 11500}
   {:band/no 35, :band/from 173000, :band/to 175000, :band/kou [3410 1800 170 0 0 0 0 0], :band/otsu 11800}
   {:band/no 36, :band/from 175000, :band/to 177000, :band/kou [3480 1870 250 0 0 0 0 0], :band/otsu 12100}
   {:band/no 37, :band/from 177000, :band/to 179000, :band/kou [3550 1940 320 0 0 0 0 0], :band/otsu 12500}
   {:band/no 38, :band/from 179000, :band/to 181000, :band/kou [3620 2010 390 0 0 0 0 0], :band/otsu 12800}
   {:band/no 39, :band/from 181000, :band/to 183000, :band/kou [3700 2080 460 0 0 0 0 0], :band/otsu 13300}
   {:band/no 40, :band/from 183000, :band/to 185000, :band/kou [3770 2150 530 0 0 0 0 0], :band/otsu 14000}
   {:band/no 41, :band/from 185000, :band/to 187000, :band/kou [3840 2230 600 0 0 0 0 0], :band/otsu 14700}
   {:band/no 42, :band/from 187000, :band/to 189000, :band/kou [3910 2300 670 0 0 0 0 0], :band/otsu 15400}
   {:band/no 43, :band/from 189000, :band/to 191000, :band/kou [3980 2370 750 0 0 0 0 0], :band/otsu 16100}
   {:band/no 44, :band/from 191000, :band/to 193000, :band/kou [4050 2440 820 0 0 0 0 0], :band/otsu 16800}
   {:band/no 45, :band/from 193000, :band/to 195000, :band/kou [4120 2510 890 0 0 0 0 0], :band/otsu 17600}
   {:band/no 46, :band/from 195000, :band/to 197000, :band/kou [4200 2580 960 0 0 0 0 0], :band/otsu 18300}
   {:band/no 47, :band/from 197000, :band/to 199000, :band/kou [4270 2650 1030 0 0 0 0 0], :band/otsu 19000}
   {:band/no 48, :band/from 199000, :band/to 201000, :band/kou [4340 2730 1100 0 0 0 0 0], :band/otsu 19700}
   {:band/no 49, :band/from 201000, :band/to 203000, :band/kou [4410 2800 1170 0 0 0 0 0], :band/otsu 20400}
   {:band/no 50, :band/from 203000, :band/to 205000, :band/kou [4480 2870 1250 0 0 0 0 0], :band/otsu 21000}
   {:band/no 51, :band/from 205000, :band/to 207000, :band/kou [4550 2940 1320 0 0 0 0 0], :band/otsu 21700}
   {:band/no 52, :band/from 207000, :band/to 209000, :band/kou [4630 3010 1390 0 0 0 0 0], :band/otsu 22500}
   {:band/no 53, :band/from 209000, :band/to 211000, :band/kou [4700 3080 1460 0 0 0 0 0], :band/otsu 23000}
   {:band/no 54, :band/from 211000, :band/to 213000, :band/kou [4770 3150 1530 0 0 0 0 0], :band/otsu 23600}
   {:band/no 55, :band/from 213000, :band/to 215000, :band/kou [4840 3230 1600 0 0 0 0 0], :band/otsu 24100}
   {:band/no 56, :band/from 215000, :band/to 217000, :band/kou [4910 3300 1670 0 0 0 0 0], :band/otsu 24700}
   {:band/no 57, :band/from 217000, :band/to 219000, :band/kou [4980 3370 1750 130 0 0 0 0], :band/otsu 25300}
   {:band/no 58, :band/from 219000, :band/to 221000, :band/kou [5050 3440 1820 200 0 0 0 0], :band/otsu 25800}
   {:band/no 59, :band/from 221000, :band/to 224000, :band/kou [5150 3520 1910 300 0 0 0 0], :band/otsu 26400}
   {:band/no 60, :band/from 224000, :band/to 227000, :band/kou [5250 3630 2020 400 0 0 0 0], :band/otsu 27500}
   {:band/no 61, :band/from 227000, :band/to 230000, :band/kou [5360 3740 2120 510 0 0 0 0], :band/otsu 28500}
   {:band/no 62, :band/from 230000, :band/to 233000, :band/kou [5460 3850 2240 610 0 0 0 0], :band/otsu 29500}
   {:band/no 63, :band/from 233000, :band/to 236000, :band/kou [5570 3950 2340 720 0 0 0 0], :band/otsu 30500}
   {:band/no 64, :band/from 236000, :band/to 239000, :band/kou [5680 4060 2450 830 0 0 0 0], :band/otsu 31500}
   {:band/no 65, :band/from 239000, :band/to 242000, :band/kou [5790 4170 2550 940 0 0 0 0], :band/otsu 32600}
   {:band/no 66, :band/from 242000, :band/to 245000, :band/kou [5890 4280 2660 1040 0 0 0 0], :band/otsu 33600}
   {:band/no 67, :band/from 245000, :band/to 248000, :band/kou [6000 4380 2770 1150 0 0 0 0], :band/otsu 34600}
   {:band/no 68, :band/from 248000, :band/to 251000, :band/kou [6110 4490 2880 1260 0 0 0 0], :band/otsu 35500}
   {:band/no 69, :band/from 251000, :band/to 254000, :band/kou [6220 4590 2980 1370 0 0 0 0], :band/otsu 36600}
   {:band/no 70, :band/from 254000, :band/to 257000, :band/kou [6320 4710 3090 1470 0 0 0 0], :band/otsu 37600}
   {:band/no 71, :band/from 257000, :band/to 260000, :band/kou [6430 4810 3200 1580 0 0 0 0], :band/otsu 38600}
   {:band/no 72, :band/from 260000, :band/to 263000, :band/kou [6530 4920 3310 1680 0 0 0 0], :band/otsu 39600}
   {:band/no 73, :band/from 263000, :band/to 266000, :band/kou [6650 5020 3410 1800 170 0 0 0], :band/otsu 40600}
   {:band/no 74, :band/from 266000, :band/to 269000, :band/kou [6750 5140 3520 1900 290 0 0 0], :band/otsu 41700}
   {:band/no 75, :band/from 269000, :band/to 272000, :band/kou [6860 5240 3620 2010 390 0 0 0], :band/otsu 42700}
   {:band/no 76, :band/from 272000, :band/to 275000, :band/kou [6960 5350 3740 2110 500 0 0 0], :band/otsu 43700}
   {:band/no 77, :band/from 275000, :band/to 278000, :band/kou [7080 5450 3840 2230 600 0 0 0], :band/otsu 44700}
   {:band/no 78, :band/from 278000, :band/to 281000, :band/kou [7180 5560 3950 2330 710 0 0 0], :band/otsu 45600}
   {:band/no 79, :band/from 281000, :band/to 284000, :band/kou [7290 5670 4050 2440 820 0 0 0], :band/otsu 46700}
   {:band/no 80, :band/from 284000, :band/to 287000, :band/kou [7390 5780 4170 2540 930 0 0 0], :band/otsu 47800}
   {:band/no 81, :band/from 287000, :band/to 290000, :band/kou [7500 5880 4270 2650 1030 0 0 0], :band/otsu 48900}
   {:band/no 82, :band/from 290000, :band/to 293000, :band/kou [7610 5990 4380 2760 1140 0 0 0], :band/otsu 50000}
   {:band/no 83, :band/from 293000, :band/to 296000, :band/kou [7720 6100 4480 2870 1250 0 0 0], :band/otsu 51300}
   {:band/no 84, :band/from 296000, :band/to 299000, :band/kou [7820 6210 4590 2970 1360 0 0 0], :band/otsu 52400}
   {:band/no 85, :band/from 299000, :band/to 302000, :band/kou [7930 6320 4700 3080 1470 0 0 0], :band/otsu 53600}
   {:band/no 86, :band/from 302000, :band/to 305000, :band/kou [8060 6440 4820 3210 1590 0 0 0], :band/otsu 54500}
   {:band/no 87, :band/from 305000, :band/to 308000, :band/kou [8180 6570 4940 3330 1720 0 0 0], :band/otsu 55200}
   {:band/no 88, :band/from 308000, :band/to 311000, :band/kou [8300 6690 5060 3450 1840 210 0 0], :band/otsu 56100}
   {:band/no 89, :band/from 311000, :band/to 314000, :band/kou [8550 6810 5190 3570 1960 340 0 0], :band/otsu 56900}
   {:band/no 90, :band/from 314000, :band/to 317000, :band/kou [8790 6930 5310 3700 2080 460 0 0], :band/otsu 57700}
   {:band/no 91, :band/from 317000, :band/to 320000, :band/kou [9040 7060 5430 3820 2210 580 0 0], :band/otsu 58500}
   {:band/no 92, :band/from 320000, :band/to 323000, :band/kou [9280 7180 5550 3940 2330 700 0 0], :band/otsu 59500}
   {:band/no 93, :band/from 323000, :band/to 326000, :band/kou [9530 7300 5680 4060 2450 830 0 0], :band/otsu 60500}
   {:band/no 94, :band/from 326000, :band/to 329000, :band/kou [9770 7420 5800 4190 2570 950 0 0], :band/otsu 61600}
   {:band/no 95, :band/from 329000, :band/to 332000, :band/kou [10020 7550 5920 4310 2700 1070 0 0], :band/otsu 62600}
   {:band/no 96, :band/from 332000, :band/to 335000, :band/kou [10260 7670 6040 4430 2820 1190 0 0], :band/otsu 63700}
   {:band/no 97, :band/from 335000, :band/to 338000, :band/kou [10510 7790 6170 4550 2940 1320 0 0], :band/otsu 64700}
   {:band/no 98, :band/from 338000, :band/to 341000, :band/kou [10750 7910 6290 4680 3060 1440 0 0], :band/otsu 65800}
   {:band/no 99, :band/from 341000, :band/to 344000, :band/kou [11000 8040 6410 4800 3190 1560 0 0], :band/otsu 66800}
   {:band/no 100, :band/from 344000, :band/to 347000, :band/kou [11240 8160 6530 4920 3310 1680 0 0], :band/otsu 67800}
   {:band/no 101, :band/from 347000, :band/to 350000, :band/kou [11490 8280 6660 5040 3430 1810 190 0], :band/otsu 68800}
   {:band/no 102, :band/from 350000, :band/to 353000, :band/kou [11730 8500 6780 5170 3550 1930 320 0], :band/otsu 69800}
   {:band/no 103, :band/from 353000, :band/to 356000, :band/kou [11980 8750 6900 5290 3680 2050 440 0], :band/otsu 70900}
   {:band/no 104, :band/from 356000, :band/to 359000, :band/kou [12220 9000 7020 5410 3800 2170 560 0], :band/otsu 71900}
   {:band/no 105, :band/from 359000, :band/to 362000, :band/kou [12470 9240 7150 5530 3920 2300 680 0], :band/otsu 72900}
   {:band/no 106, :band/from 362000, :band/to 365000, :band/kou [12710 9490 7270 5660 4040 2420 810 0], :band/otsu 73900}
   {:band/no 107, :band/from 365000, :band/to 368000, :band/kou [12960 9730 7390 5780 4170 2540 930 0], :band/otsu 74900}
   {:band/no 108, :band/from 368000, :band/to 371000, :band/kou [13200 9980 7510 5900 4290 2660 1050 0], :band/otsu 76000}
   {:band/no 109, :band/from 371000, :band/to 374000, :band/kou [13450 10220 7640 6020 4410 2790 1170 0], :band/otsu 76900}
   {:band/no 110, :band/from 374000, :band/to 377000, :band/kou [13690 10470 7760 6150 4530 2910 1300 0], :band/otsu 77800}
   {:band/no 111, :band/from 377000, :band/to 380000, :band/kou [13940 10710 7880 6270 4660 3030 1420 0], :band/otsu 78700}
   {:band/no 112, :band/from 380000, :band/to 383000, :band/kou [14180 10960 8000 6390 4780 3150 1540 0], :band/otsu 79600}
   {:band/no 113, :band/from 383000, :band/to 386000, :band/kou [14430 11200 8130 6510 4900 3280 1660 0], :band/otsu 80600}
   {:band/no 114, :band/from 386000, :band/to 389000, :band/kou [14670 11450 8250 6640 5020 3400 1790 170], :band/otsu 82000}
   {:band/no 115, :band/from 389000, :band/to 392000, :band/kou [14920 11690 8450 6760 5150 3520 1910 300], :band/otsu 83600}
   {:band/no 116, :band/from 392000, :band/to 395000, :band/kou [15160 11940 8700 6880 5270 3640 2030 420], :band/otsu 85400}
   {:band/no 117, :band/from 395000, :band/to 398000, :band/kou [15410 12180 8940 7000 5390 3770 2150 540], :band/otsu 87100}
   {:band/no 118, :band/from 398000, :band/to 401000, :band/kou [15650 12430 9190 7130 5510 3890 2280 660], :band/otsu 88700}
   {:band/no 119, :band/from 401000, :band/to 404000, :band/kou [15900 12670 9430 7250 5640 4010 2400 790], :band/otsu 90500}
   {:band/no 120, :band/from 404000, :band/to 407000, :band/kou [16140 12920 9680 7370 5760 4140 2520 910], :band/otsu 92200}
   {:band/no 121, :band/from 407000, :band/to 410000, :band/kou [16390 13160 9920 7490 5880 4260 2640 1030], :band/otsu 93800}
   {:band/no 122, :band/from 410000, :band/to 413000, :band/kou [16630 13410 10170 7620 6000 4380 2770 1150], :band/otsu 95600}
   {:band/no 123, :band/from 413000, :band/to 416000, :band/kou [16880 13650 10410 7740 6130 4500 2890 1280], :band/otsu 97300}
   {:band/no 124, :band/from 416000, :band/to 419000, :band/kou [17120 13900 10660 7860 6250 4630 3010 1400], :band/otsu 98900}
   {:band/no 125, :band/from 419000, :band/to 422000, :band/kou [17370 14140 10900 7980 6370 4750 3130 1520], :band/otsu 100700}
   {:band/no 126, :band/from 422000, :band/to 425000, :band/kou [17610 14390 11150 8110 6490 4870 3260 1640], :band/otsu 102400}
   {:band/no 127, :band/from 425000, :band/to 428000, :band/kou [17860 14630 11390 8230 6620 4990 3380 1770], :band/otsu 104000}
   {:band/no 128, :band/from 428000, :band/to 431000, :band/kou [18100 14880 11640 8400 6740 5120 3500 1890], :band/otsu 105800}
   {:band/no 129, :band/from 431000, :band/to 434000, :band/kou [18350 15120 11880 8650 6860 5240 3620 2010], :band/otsu 107500}
   {:band/no 130, :band/from 434000, :band/to 437000, :band/kou [18590 15370 12130 8890 6980 5360 3750 2130], :band/otsu 109100}
   {:band/no 131, :band/from 437000, :band/to 440000, :band/kou [18840 15610 12370 9140 7110 5480 3870 2260], :band/otsu 110900}
   {:band/no 132, :band/from 440000, :band/to 443000, :band/kou [19080 15860 12620 9380 7230 5610 3990 2380], :band/otsu 112600}
   {:band/no 133, :band/from 443000, :band/to 446000, :band/kou [19330 16100 12860 9630 7350 5730 4110 2500], :band/otsu 114200}
   {:band/no 134, :band/from 446000, :band/to 449000, :band/kou [19570 16350 13110 9870 7470 5850 4240 2620], :band/otsu 116000}
   {:band/no 135, :band/from 449000, :band/to 452000, :band/kou [19860 16590 13350 10120 7600 5970 4360 2750], :band/otsu 117600}
   {:band/no 136, :band/from 452000, :band/to 455000, :band/kou [20350 16840 13600 10360 7720 6100 4480 2870], :band/otsu 119400}
   {:band/no 137, :band/from 455000, :band/to 458000, :band/kou [20840 17080 13840 10610 7840 6220 4600 2990], :band/otsu 121100}
   {:band/no 138, :band/from 458000, :band/to 461000, :band/kou [21330 17330 14090 10850 7960 6340 4730 3110], :band/otsu 122700}
   {:band/no 139, :band/from 461000, :band/to 464000, :band/kou [21820 17570 14330 11100 8090 6460 4850 3240], :band/otsu 124500}
   {:band/no 140, :band/from 464000, :band/to 467000, :band/kou [22310 17820 14580 11340 8210 6590 4970 3360], :band/otsu 126200}
   {:band/no 141, :band/from 467000, :band/to 470000, :band/kou [22800 18060 14820 11590 8360 6710 5090 3480], :band/otsu 127800}
   {:band/no 142, :band/from 470000, :band/to 473000, :band/kou [23290 18310 15070 11830 8610 6830 5220 3600], :band/otsu 129600}
   {:band/no 143, :band/from 473000, :band/to 476000, :band/kou [23780 18550 15320 12080 8850 6950 5340 3730], :band/otsu 131200}
   {:band/no 144, :band/from 476000, :band/to 479000, :band/kou [24270 18800 15560 12320 9100 7080 5460 3850], :band/otsu 132800}
   {:band/no 145, :band/from 479000, :band/to 482000, :band/kou [24760 19040 15810 12570 9340 7200 5580 3970], :band/otsu 134500}
   {:band/no 146, :band/from 482000, :band/to 485000, :band/kou [25250 19290 16050 12810 9590 7320 5710 4090], :band/otsu 136100}
   {:band/no 147, :band/from 485000, :band/to 488000, :band/kou [25740 19530 16300 13060 9830 7440 5830 4220], :band/otsu 137600}
   {:band/no 148, :band/from 488000, :band/to 491000, :band/kou [26230 19780 16540 13300 10080 7570 5950 4340], :band/otsu 139300}
   {:band/no 149, :band/from 491000, :band/to 494000, :band/kou [26720 20260 16790 13550 10320 7690 6070 4460], :band/otsu 140900}
   {:band/no 150, :band/from 494000, :band/to 497000, :band/kou [27210 20750 17030 13790 10570 7810 6200 4580], :band/otsu 142500}
   {:band/no 151, :band/from 497000, :band/to 500000, :band/kou [27700 21240 17280 14040 10810 7930 6320 4710], :band/otsu 144100}
   {:band/no 152, :band/from 500000, :band/to 503000, :band/kou [28190 21730 17520 14280 11060 8060 6440 4830], :band/otsu 145700}
   {:band/no 153, :band/from 503000, :band/to 506000, :band/kou [28680 22220 17770 14530 11300 8180 6570 4950], :band/otsu 147300}
   {:band/no 154, :band/from 506000, :band/to 509000, :band/kou [29170 22710 18010 14770 11550 8310 6690 5070], :band/otsu 149000}
   {:band/no 155, :band/from 509000, :band/to 512000, :band/kou [29660 23200 18260 15020 11790 8560 6810 5200], :band/otsu 150500}
   {:band/no 156, :band/from 512000, :band/to 515000, :band/kou [30150 23690 18500 15260 12040 8800 6930 5320], :band/otsu 152100}
   {:band/no 157, :band/from 515000, :band/to 518000, :band/kou [30640 24180 18750 15510 12280 9050 7060 5440], :band/otsu 153800}
   {:band/no 158, :band/from 518000, :band/to 521000, :band/kou [31130 24670 18990 15750 12530 9290 7180 5560], :band/otsu 155400}
   {:band/no 159, :band/from 521000, :band/to 524000, :band/kou [31620 25160 19240 16000 12770 9540 7300 5690], :band/otsu 156900}
   {:band/no 160, :band/from 524000, :band/to 527000, :band/kou [32110 25650 19480 16240 13020 9780 7420 5810], :band/otsu 158600}
   {:band/no 161, :band/from 527000, :band/to 530000, :band/kou [32600 26140 19730 16490 13260 10030 7550 5930], :band/otsu 160200}
   {:band/no 162, :band/from 530000, :band/to 533000, :band/kou [33090 26630 20160 16730 13510 10270 7670 6050], :band/otsu 161600}
   {:band/no 163, :band/from 533000, :band/to 536000, :band/kou [33580 27120 20650 16980 13750 10520 7790 6180], :band/otsu 163200}
   {:band/no 164, :band/from 536000, :band/to 539000, :band/kou [34070 27610 21140 17220 14000 10760 7910 6300], :band/otsu 164600}
   {:band/no 165, :band/from 539000, :band/to 542000, :band/kou [34560 28100 21630 17470 14240 11010 8040 6420], :band/otsu 166000}
   {:band/no 166, :band/from 542000, :band/to 545000, :band/kou [35050 28590 22130 17710 14490 11250 8160 6540], :band/otsu 167500}
   {:band/no 167, :band/from 545000, :band/to 548000, :band/kou [35540 29080 22620 17960 14730 11500 8280 6670], :band/otsu 169000}
   {:band/no 168, :band/from 548000, :band/to 551000, :band/kou [36030 29570 23110 18200 14980 11740 8500 6790], :band/otsu 170500}
   {:band/no 169, :band/from 551000, :band/to 554000, :band/kou [36570 30110 23650 18480 15240 12020 8780 6920], :band/otsu 171900}
   {:band/no 170, :band/from 554000, :band/to 557000, :band/kou [37120 30660 24200 18760 15520 12290 9060 7060], :band/otsu 173400}
   {:band/no 171, :band/from 557000, :band/to 560000, :band/kou [37670 31210 24750 19030 15790 12570 9330 7200], :band/otsu 174900}
   {:band/no 172, :band/from 560000, :band/to 563000, :band/kou [38230 31760 25300 19310 16070 12840 9610 7330], :band/otsu 176300}
   {:band/no 173, :band/from 563000, :band/to 566000, :band/kou [38780 32310 25850 19580 16350 13120 9880 7470], :band/otsu 177900}
   {:band/no 174, :band/from 566000, :band/to 569000, :band/kou [39330 32870 26400 19930 16620 13400 10160 7610], :band/otsu 179300}
   {:band/no 175, :band/from 569000, :band/to 572000, :band/kou [39880 33420 26950 20480 16900 13670 10430 7750], :band/otsu 180700}
   {:band/no 176, :band/from 572000, :band/to 575000, :band/kou [40430 33970 27510 21030 17170 13950 10710 7880], :band/otsu 182200}
   {:band/no 177, :band/from 575000, :band/to 578000, :band/kou [40980 34520 28060 21580 17450 14220 10990 8030], :band/otsu 183700}
   {:band/no 178, :band/from 578000, :band/to 581000, :band/kou [41530 35070 28610 22140 17720 14500 11260 8160], :band/otsu 185200}
   {:band/no 179, :band/from 581000, :band/to 584000, :band/kou [42090 35620 29160 22690 18000 14770 11540 8300], :band/otsu 186600}
   {:band/no 180, :band/from 584000, :band/to 587000, :band/kou [42640 36170 29710 23240 18280 15050 11810 8580], :band/otsu 188100}
   {:band/no 181, :band/from 587000, :band/to 590000, :band/kou [43190 36730 30260 23790 18550 15330 12090 8850], :band/otsu 189600}
   {:band/no 182, :band/from 590000, :band/to 593000, :band/kou [43740 37280 30810 24340 18830 15600 12360 9130], :band/otsu 191000}
   {:band/no 183, :band/from 593000, :band/to 596000, :band/kou [44290 37830 31370 24890 19100 15880 12640 9400], :band/otsu 192600}
   {:band/no 184, :band/from 596000, :band/to 599000, :band/kou [44840 38380 31920 25440 19380 16150 12920 9680], :band/otsu 194000}
   {:band/no 185, :band/from 599000, :band/to 602000, :band/kou [45390 38930 32470 25990 19650 16430 13190 9950], :band/otsu 195400}
   {:band/no 186, :band/from 602000, :band/to 605000, :band/kou [45950 39480 33020 26550 20080 16700 13470 10230], :band/otsu 197000}
   {:band/no 187, :band/from 605000, :band/to 608000, :band/kou [46500 40030 33570 27100 20630 16980 13740 10510], :band/otsu 198400}
   {:band/no 188, :band/from 608000, :band/to 611000, :band/kou [47050 40580 34120 27650 21190 17250 14020 10780], :band/otsu 199900}
   {:band/no 189, :band/from 611000, :band/to 614000, :band/kou [47600 41140 34670 28200 21740 17530 14290 11060], :band/otsu 201300}
   {:band/no 190, :band/from 614000, :band/to 617000, :band/kou [48150 41690 35220 28750 22290 17810 14570 11330], :band/otsu 202800}
   {:band/no 191, :band/from 617000, :band/to 620000, :band/kou [48700 42240 35780 29300 22840 18080 14850 11610], :band/otsu 204300}
   {:band/no 192, :band/from 620000, :band/to 623000, :band/kou [49250 42790 36330 29850 23390 18360 15120 11880], :band/otsu 205700}
   {:band/no 193, :band/from 623000, :band/to 626000, :band/kou [49800 43340 36880 30410 23940 18630 15400 12160], :band/otsu 207300}
   {:band/no 194, :band/from 626000, :band/to 629000, :band/kou [50360 43890 37430 30960 24490 18910 15670 12440], :band/otsu 208700}
   {:band/no 195, :band/from 629000, :band/to 632000, :band/kou [50910 44440 37980 31510 25050 19180 15950 12710], :band/otsu 210100}
   {:band/no 196, :band/from 632000, :band/to 635000, :band/kou [51460 45000 38530 32060 25600 19460 16220 12990], :band/otsu 211700}
   {:band/no 197, :band/from 635000, :band/to 638000, :band/kou [52010 45550 39080 32610 26150 19740 16500 13260], :band/otsu 213100}
   {:band/no 198, :band/from 638000, :band/to 641000, :band/kou [52560 46100 39640 33160 26700 20240 16780 13540], :band/otsu 214600}
   {:band/no 199, :band/from 641000, :band/to 644000, :band/kou [53110 46650 40190 33710 27250 20790 17050 13810], :band/otsu 215900}
   {:band/no 200, :band/from 644000, :band/to 647000, :band/kou [53660 47200 40740 34260 27800 21340 17330 14090], :band/otsu 217000}
   {:band/no 201, :band/from 647000, :band/to 650000, :band/kou [54220 47750 41290 34820 28350 21890 17600 14370], :band/otsu 218000}
   {:band/no 202, :band/from 650000, :band/to 653000, :band/kou [54770 48300 41840 35370 28900 22440 17880 14640], :band/otsu 219000}
   {:band/no 203, :band/from 653000, :band/to 656000, :band/kou [55320 48850 42390 35920 29460 22990 18150 14920], :band/otsu 220000}
   {:band/no 204, :band/from 656000, :band/to 659000, :band/kou [55870 49410 42940 36470 30010 23540 18430 15190], :band/otsu 221000}
   {:band/no 205, :band/from 659000, :band/to 662000, :band/kou [56420 49960 43490 37020 30560 24100 18700 15470], :band/otsu 222100}
   {:band/no 206, :band/from 662000, :band/to 665000, :band/kou [56970 50510 44050 37570 31110 24650 18980 15740], :band/otsu 223100}
   {:band/no 207, :band/from 665000, :band/to 668000, :band/kou [57520 51060 44600 38120 31660 25200 19260 16020], :band/otsu 224100}
   {:band/no 208, :band/from 668000, :band/to 671000, :band/kou [58070 51610 45150 38680 32210 25750 19530 16300], :band/otsu 225000}
   {:band/no 209, :band/from 671000, :band/to 674000, :band/kou [58630 52160 45700 39230 32760 26300 19830 16570], :band/otsu 226000}
   {:band/no 210, :band/from 674000, :band/to 677000, :band/kou [59180 52710 46250 39780 33320 26850 20380 16850], :band/otsu 227100}
   {:band/no 211, :band/from 677000, :band/to 680000, :band/kou [59730 53270 46800 40330 33870 27400 20930 17120], :band/otsu 228100}
   {:band/no 212, :band/from 680000, :band/to 683000, :band/kou [60280 53820 47350 40880 34420 27950 21480 17400], :band/otsu 229100}
   {:band/no 213, :band/from 683000, :band/to 686000, :band/kou [60830 54370 47910 41430 34970 28510 22030 17670], :band/otsu 230100}
   {:band/no 214, :band/from 686000, :band/to 689000, :band/kou [61380 54920 48460 41980 35520 29060 22580 17950], :band/otsu 231500}
   {:band/no 215, :band/from 689000, :band/to 692000, :band/kou [61930 55470 49010 42530 36070 29610 23140 18220], :band/otsu 233000}
   {:band/no 216, :band/from 692000, :band/to 695000, :band/kou [62490 56020 49560 43090 36620 30160 23690 18500], :band/otsu 234500}
   {:band/no 217, :band/from 695000, :band/to 698000, :band/kou [63040 56570 50110 43640 37170 30710 24240 18780], :band/otsu 236100}
   {:band/no 218, :band/from 698000, :band/to 701000, :band/kou [63590 57120 50660 44190 37730 31260 24790 19050], :band/otsu 237600}
   {:band/no 219, :band/from 701000, :band/to 704000, :band/kou [64140 57680 51210 44740 38280 31810 25340 19330], :band/otsu 239100}
   {:band/no 220, :band/from 704000, :band/to 707000, :band/kou [64690 58230 51760 45290 38830 32370 25890 19600], :band/otsu 240800}
   {:band/no 221, :band/from 707000, :band/to 710000, :band/kou [65250 58780 52320 45850 39380 32920 26450 19980], :band/otsu 242300}
   {:band/no 222, :band/from 710000, :band/to 713000, :band/kou [65860 59390 52930 46470 39990 33530 27070 20590], :band/otsu 243800}
   {:band/no 223, :band/from 713000, :band/to 716000, :band/kou [66480 60000 53540 47080 40610 34140 27680 21210], :band/otsu 245300}
   {:band/no 224, :band/from 716000, :band/to 719000, :band/kou [67090 60620 54150 47690 41220 34750 28290 21820], :band/otsu 246900}
   {:band/no 225, :band/from 719000, :band/to 722000, :band/kou [67700 61230 54770 48300 41830 35370 28900 22430], :band/otsu 248400}
   {:band/no 226, :band/from 722000, :band/to 725000, :band/kou [68320 61840 55380 48920 42440 35980 29520 23040], :band/otsu 250000}
   {:band/no 227, :band/from 725000, :band/to 728000, :band/kou [68930 62450 55990 49530 43060 36590 30130 23660], :band/otsu 251600}
   {:band/no 228, :band/from 728000, :band/to 731000, :band/kou [69540 63070 56600 50140 43670 37210 30740 24270], :band/otsu 253100}
   {:band/no 229, :band/from 731000, :band/to 734000, :band/kou [70150 63680 57220 50750 44280 37820 31350 24880], :band/otsu 254600}
   {:band/no 230, :band/from 734000, :band/to 737000, :band/kou [70770 64290 57830 51370 44890 38430 31970 25490], :band/otsu 256200}
   {:band/no 231, :band/from 737000, :band/to 740000, :band/kou [71380 64900 58440 51980 45510 39040 32580 26110], :band/otsu 257700}])

;; ---------------------------------------------------------------------------
;; The nine threshold rows
;; ---------------------------------------------------------------------------

(def thresholds
  "The tax at nine exact amounts, printed rather than derived.

  These are the amounts where the table stops being banded and starts being
  a formula. The workbook prints the tax AT each of them, so those amounts
  are answerable exactly — and they are also the bases the excess-rate
  segments add to, which is why `kou-segments` takes its `:segment/base`
  from here instead of restating it.

  `:threshold/otsu` is nil for seven of the nine. That is the workbook's
  shape, not a gap in this import: 乙 above 740,000円 is stated once as a
  formula rather than row by row."
  [{:threshold/at 740000, :threshold/kou [71680 65210 58750 52290 45810 39350 32890 26410], :threshold/otsu 259200}
   {:threshold/at 790000, :threshold/kou [81890 75420 68960 62500 56020 49560 43100 36620], :threshold/otsu nil}
   {:threshold/at 960000, :threshold/kou [121820 115340 108880 102420 95940 89480 83020 76540], :threshold/otsu nil}
   {:threshold/at 1710000, :threshold/kou [374520 368040 361580 355120 348640 342180 335720 329240], :threshold/otsu 655400}
   {:threshold/at 2130000, :threshold/kou [549440 542970 536500 530040 523570 517110 510640 504170], :threshold/otsu nil}
   {:threshold/at 2170000, :threshold/kou [571220 564750 558280 551820 545350 538880 532420 525950], :threshold/otsu nil}
   {:threshold/at 2210000, :threshold/kou [593000 586520 580060 573600 567120 560660 554200 547730], :threshold/otsu nil}
   {:threshold/at 2250000, :threshold/kou [614770 608300 601840 595380 588900 582440 575980 569500], :threshold/otsu nil}
   {:threshold/at 3500000, :threshold/kou [1125270 1118800 1112340 1105880 1099400 1092940 1086480 1080000], :threshold/otsu nil}])

;; ---------------------------------------------------------------------------
;; The excess-rate tail
;; ---------------------------------------------------------------------------

(def kou-segments
  "甲欄 above 740,000円: base + rate × (amount − threshold).

  `:segment/rate` is an exact ratio. `:segment/to` is nil on the last
  segment, which is open ended — 3,500,000円を超える金額 has no upper edge.

  This is a FORMULA and not an answer. The workbook states the rate and
  does not state the 端数処理 for the yen fraction it produces, so
  `payroll.rates/withhold` reports the exact rational and refuses to round
  it. `:segment/basis` is the sentence that was parsed, kept so that the
  claim can be checked against the printed page."
  [{:segment/from 740000, :segment/to 790000, :segment/base [71680 65210 58750 52290 45810 39350 32890 26410], :segment/rate 1021/5000, :segment/basis "740,000円を超える金額の20.42％に相当する金額を加算した金額"}
   {:segment/from 790000, :segment/to 960000, :segment/base [81890 75420 68960 62500 56020 49560 43100 36620], :segment/rate 23483/100000, :segment/basis "790,000円を超える金額の23.483％に相当する金額を加算した金額"}
   {:segment/from 960000, :segment/to 1710000, :segment/base [121820 115340 108880 102420 95940 89480 83020 76540], :segment/rate 33693/100000, :segment/basis "960,000円を超える金額の33.693％に相当する金額を加算した金額"}
   {:segment/from 1710000, :segment/to 2130000, :segment/base [374520 368040 361580 355120 348640 342180 335720 329240], :segment/rate 1021/2500, :segment/basis "1,710,000円を超える金額の40.84％に相当する金額を加算した金額"}
   {:segment/from 2130000, :segment/to 2170000, :segment/base [549440 542970 536500 530040 523570 517110 510640 504170], :segment/rate 1021/2500, :segment/basis "2,130,000円を超える金額の40.84％に相当する金額を加算した金額"}
   {:segment/from 2170000, :segment/to 2210000, :segment/base [571220 564750 558280 551820 545350 538880 532420 525950], :segment/rate 1021/2500, :segment/basis "2,170,000円を超える金額の40.84％に相当する金額を加算した金額"}
   {:segment/from 2210000, :segment/to 2250000, :segment/base [593000 586520 580060 573600 567120 560660 554200 547730], :segment/rate 1021/2500, :segment/basis "2,210,000円を超える金額の40.84％に相当する金額を加算した金額"}
   {:segment/from 2250000, :segment/to 3500000, :segment/base [614770 608300 601840 595380 588900 582440 575980 569500], :segment/rate 1021/2500, :segment/basis "2,250,000円を超える金額の40.84％に相当する金額を加算した金額"}
   {:segment/from 3500000, :segment/to nil, :segment/base [1125270 1118800 1112340 1105880 1099400 1092940 1086480 1080000], :segment/rate 9189/20000, :segment/basis "3,500,000円を超える金額の45.945％に相当する金額を加算した金額"}])

(def otsu-segments
  "乙欄 above 740,000円.

  Two segments rather than nine, and `:segment/base` is a single amount
  rather than eight, because 乙欄 does not depend on 扶養親族等の数 —
  it is the column for somebody who filed no 扶養控除等申告書 at all."
  [{:segment/from 740000, :segment/to 1710000, :segment/base 259200, :segment/rate 1021/2500, :segment/basis "259,200円に、その月の社会保険料等控除後の給与等の金額のうち740,000円を超える金額の40.84％に相当する金額を加算した金額"}
   {:segment/from 1710000, :segment/to nil, :segment/base 655400, :segment/rate 9189/20000, :segment/basis "655,400円に、その月の社会保険料等控除後の給与等の金額のうち1,710,000円を超える金額の45.945％に相当する金額を加算した金額"}])

;; ---------------------------------------------------------------------------
;; 扶養親族等の数が７人を超える場合
;; ---------------------------------------------------------------------------

(def dependants-beyond-7-deduction
  "「扶養親族等の数が７人を超える場合には、扶養親族等の数が７人の場合の税額
  から、その７人を超える１人ごとに1,610円を控除した金額」

  Read from the workbook, and read from all three places it says it, which
  must agree. What the workbook does NOT say is what happens when the
  subtraction exceeds the tax. `payroll.rates/withhold` floors the result at
  zero and says so in `dependant-adjust`'s docstring — a negative
  源泉徴収税額 would be a refund the 月額表 does not provide for. That floor
  is THIS repository's reading and is not printed here, because this file
  holds only what the workbook printed."
  1610)
