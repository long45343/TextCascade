### MEM Usage on physical machine(Qualcomm Snapdragon 8 gen 3)
```
Applications Memory Usage (in Kilobytes):
Uptime: 153823638 Realtime: 236887830

** MEMINFO in pid 20312 [com.textcascad.v2] **
                   Pss  Private  Private  SwapPss      Rss     Heap     Heap     Heap
                 Total    Dirty    Clean    Dirty    Total     Size    Alloc     Free
                ------   ------   ------   ------   ------   ------   ------   ------
  Native Heap      735      668       64    15992     2204    36428    22429    10132
  Dalvik Heap     1428     1332       44     2935     7196    10836     2644     8192
 Dalvik Other      328      188       48     1313     1316
        Stack      240      220       20      784      252
       Ashmem        1        0        0        0      356
    Other dev       12        0       12        0      452
     .so mmap     2477       20       20      547    48172
    .jar mmap      250        0        0        0    21992
    .apk mmap        6        0        0        0      356
    .ttf mmap      368        0        0        0     6504
    .dex mmap        4        0        0        0     1048
    .oat mmap      216        0        0        0    15096
    .art mmap      415      180       56      491    21192
   Other mmap      178        0      176        4     1052
      Unknown      135       84       48      805      956
        TOTAL    29664     2692      488    22871   128144    47264    25073    18324

 App Summary
                       Pss(KB)                        Rss(KB)
                        ------                         ------
           Java Heap:     1568                          28388
         Native Heap:      668                           2204
                Code:       40                          93344
               Stack:      220                            252
            Graphics:        0                              0
       Private Other:      684
              System:    26484
             Unknown:                                    3956

           TOTAL PSS:    29664            TOTAL RSS:   128144       TOTAL SWAP PSS:    22871

 Objects
               Views:        0         ViewRootImpl:        0
         AppContexts:        4           Activities:        0
              Assets:       32        AssetManagers:        0
       Local Binders:       19        Proxy Binders:       94
       Parcel memory:       15         Parcel count:       58
    Death Recipients:        3             WebViews:        0

 Native Allocations
                         Count                       Total(kB)
                        ------                         ------
   Bitmap (malloced):       86                           6082
    Other (malloced):     1343                            126
 Other (nonmalloced):      153                            114

 SQL
         MEMORY_USED:        0
  PAGECACHE_OVERFLOW:        0          MALLOC_SIZE:        0
```
### CPU Usage on physical machine(Qualcomm Snapdragon 8 gen 3)
```
  0.4% 20312/com.textcascad.v2: 0.1% user + 0.3% kernel / faults: 187 minor 305 major
```