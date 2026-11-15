# Announcement regression report

Mode: **LOCAL_MODEL**. Scores describe only this reviewed fictional corpus, not general model accuracy.

Created: 2026-11-15T07:05:52.124517400Z  
Corpus: v1 / `9e99f47ab001a16989de44596cdabd97c50819dee2ef00d2b6ecac371ed38690`  
Contract: events.announcement-extraction-v1 / `sha256:1b9b411a389962e450869bd6b3d982c62a65533700fe42e674ffd283c23d1b7d`

Model: `sha256:f799fb1ad223f00fc55cf4d6c12de7dc9b71d10ca427bb0ff7b644f49d9a7960`  
Endpoint: http://127.0.0.1:12434/engines/v1  
Temperature: 0.0; retries: 0; max tokens: 4096; timeout per case: 600 s.

Repair-free case passes: **10/25**. Schema-accepted cases: 20/25. Matching normalized fields: 90/125. Extraction time: 10185311 ms.

Unavailable fields count toward totals; a rejected answer cannot earn credit for expected nulls. A repaired answer can match normalized fields and still fail the case.

| Outcome | Cases |
| --- | ---: |
|PASSED|10|
|FACT_MISMATCH|10|
|REPAIRED|0|
|REJECTED|0|
|TRUNCATED|2|
|RESPONSE_ERROR|0|
|PROVIDER_ERROR|3|

| Field | Matched / total | Available | Known facts matched / expected | Unknowns preserved / expected |
| --- | ---: | ---: | ---: | ---: |
|title|20/25|20|19/24|1/1|
|date|20/25|20|17/21|3/4|
|location|11/25|20|8/21|3/4|
|format|20/25|20|18/23|2/2|
|free|19/25|20|15/20|4/5|

## Provider usage

Counters are sums of reported values only; null means unknown, never zero.

| Counter | Sum | Cases reporting / total |
| --- | ---: | ---: |
|Prompt tokens|4539|22/25|
|Completion tokens|51156|22/25|
|Total tokens|55695|22/25|
|Reasoning tokens|null|0/25|

## public-event-testdata

Explicit in-person event and free admission; the original smoke case.

Tags: in-person, free. Outcome: **PASSED**; schema accepted: true; findings: 0; elapsed: 344936 ms.

Document SHA-256: `0a9a4d06792a38baa8d5db2e288b7897db80595fbd41949416a04773363f234d`  
Expected JSON SHA-256: `a3f6c7ba629811eeae395a0956f39191313853733bf757544110a5131c9fe279`

Finish reason: STOP; error category: null.

| Field | Expected | Actual | Comparison |
| --- | --- | --- | --- |
|title|Wieczór z Javą|Wieczór z Javą|MATCH|
|date|2026-12-10|2026-12-10|MATCH|
|location|Przykładowy Dom Kultury|Przykładowy Dom Kultury|MATCH|
|format|IN\_PERSON|IN\_PERSON|MATCH|
|free|true|true|MATCH|

## online-event-testdata

A stated online URL is the location; it is never fetched.

Tags: online, free. Outcome: **PASSED**; schema accepted: true; findings: 0; elapsed: 386195 ms.

Document SHA-256: `3d9fb2453ad676f8c400d23452f9b19d75563b3f6f052a18b7ea5cd816e69c4b`  
Expected JSON SHA-256: `21a69439238a9c43a9b2cfbc4f733dabe221f63e52f12fb2eac05a0bcd2f49a4`

Finish reason: STOP; error category: null.

| Field | Expected | Actual | Comparison |
| --- | --- | --- | --- |
|title|Mapy w przeglądarce|Mapy w przeglądarce|MATCH|
|date|2027-01-15|2027-01-15|MATCH|
|location|https://spotkanie.example.org/mapy|https://spotkanie.example.org/mapy|MATCH|
|format|ONLINE|ONLINE|MATCH|
|free|true|true|MATCH|

## paid-event-testdata

Paid admission must remain false, not be confused with a missing price.

Tags: in-person, paid. Outcome: **PASSED**; schema accepted: true; findings: 0; elapsed: 211897 ms.

Document SHA-256: `cd4d062103a379df43507cbc3dde489de7b1fde960066e76786fc86e8c152277`  
Expected JSON SHA-256: `c4fec337d1e6ce601cfe317e43d6976cc132e4e45d6b6f850737c914a9de56b3`

Finish reason: STOP; error category: null.

| Field | Expected | Actual | Comparison |
| --- | --- | --- | --- |
|title|Warsztaty papierowych lampionów|Warsztaty papierowych lampionów|MATCH|
|date|2027-02-20|2027-02-20|MATCH|
|location|Przykładowa Pracownia Papieru|Przykładowa Pracownia Papieru|MATCH|
|format|IN\_PERSON|IN\_PERSON|MATCH|
|free|false|false|MATCH|

## missing-details-testdata

A title alone leaves four explicit nulls.

Tags: missing, unknown. Outcome: **PASSED**; schema accepted: true; findings: 0; elapsed: 238213 ms.

Document SHA-256: `6db270825f730d5019614999178fa73519d912c5c020684e43a3b62651f0c352`  
Expected JSON SHA-256: `b245cb42d948856132e2417c40ea381dd65efd52c56e7b7d717cfbee964f2068`

Finish reason: STOP; error category: null.

| Field | Expected | Actual | Comparison |
| --- | --- | --- | --- |
|title|Klub pomysłów|Klub pomysłów|MATCH|
|date|null|null|MATCH|
|location|null|null|MATCH|
|format|null|null|MATCH|
|free|null|null|MATCH|

## missing-price-testdata

Known venue and date do not establish an admission price.

Tags: missing, admission. Outcome: **PASSED**; schema accepted: true; findings: 0; elapsed: 536580 ms.

Document SHA-256: `c6d5bd88c6e935419b3566bb3840774aac104efca1f1cac0b571cba904544a3b`  
Expected JSON SHA-256: `a73eec72c610e7c4055a9ffe58de8fe03442ac9af454df3d40fd96e114b19d17`

Finish reason: STOP; error category: null.

| Field | Expected | Actual | Comparison |
| --- | --- | --- | --- |
|title|Rozmowy o fotografii|Rozmowy o fotografii|MATCH|
|date|2027-03-08|2027-03-08|MATCH|
|location|Przykładowa Galeria Sąsiedzka|Przykładowa Galeria Sąsiedzka|MATCH|
|format|IN\_PERSON|IN\_PERSON|MATCH|
|free|null|null|MATCH|

## hybrid-event-testdata

A physical venue and stream are explicitly one hybrid event.

Tags: hybrid, free. Outcome: **PASSED**; schema accepted: true; findings: 0; elapsed: 481623 ms.

Document SHA-256: `44dcd669ad2e891fbac45352a8d01a8dab7f26ba694238a76938c8aed40fe020`  
Expected JSON SHA-256: `16e983e0af2e796469963629c33c1280a6ae3b62687a45ca6007a1d03bd6aa08`

Finish reason: STOP; error category: null.

| Field | Expected | Actual | Comparison |
| --- | --- | --- | --- |
|title|Mosty między salą a siecią|Mosty między salą a siecią|MATCH|
|date|2027-02-18|2027-02-18|MATCH|
|location|Przykładowe Centrum Sąsiedzkie oraz https://wydarzenia.example.org/mosty|Przykładowe Centrum Sąsiedzkie oraz https://wydarzenia.example.org/mosty|MATCH|
|format|HYBRID|HYBRID|MATCH|
|free|true|true|MATCH|

## ambiguous-date-testdata

Neither candidate date is confirmed; a scalar date must remain null.

Tags: ambiguous, date. Outcome: **FACT_MISMATCH**; schema accepted: true; findings: 0; elapsed: 305838 ms.

Document SHA-256: `cade4809a99294b99b58106da395914742aef2091fd8a26910af7f767e88d7a4`  
Expected JSON SHA-256: `a456755c1bfbc551646005cd80c7f0d45c0ea7ee6cbe01935a7b9ea19d7a6537`

Finish reason: STOP; error category: null.

| Field | Expected | Actual | Comparison |
| --- | --- | --- | --- |
|title|Wieczór prototypów|Wieczór prototypów|MATCH|
|date|null|null|MATCH|
|location|Przykładowa Pracownia|Przykładowej Pracowni|MISMATCH|
|format|IN\_PERSON|IN\_PERSON|MATCH|
|free|true|true|MATCH|

## conflicting-place-testdata

Two alternative venues are equally unconfirmed; neither is an established location.

Tags: ambiguous, location. Outcome: **TRUNCATED**; schema accepted: null; findings: null; elapsed: 544396 ms.

Document SHA-256: `69866aa5ef043a32436a2117c662042e44d271d99d268af5627f21a79d9b9fc0`  
Expected JSON SHA-256: `59c28791e9db10c67fd480c0d0b29fc6f0e5a34aa1613e8662ecdea26cf24f9f`

Finish reason: LENGTH; error category: IllegalStateException.

| Field | Expected | Actual | Comparison |
| --- | --- | --- | --- |
|title|Papierowe konstrukcje|unavailable|UNAVAILABLE|
|date|2027-04-06|unavailable|UNAVAILABLE|
|location|null|unavailable|UNAVAILABLE|
|format|IN\_PERSON|unavailable|UNAVAILABLE|
|free|false|unavailable|UNAVAILABLE|

## postponed-date-testdata

A clearly superseded date is replaced by the explicitly confirmed new date.

Tags: date, correction. Outcome: **FACT_MISMATCH**; schema accepted: true; findings: 0; elapsed: 552930 ms.

Document SHA-256: `d32abc45584f3251f44b99ccd1b3e6eae7f2652087a9fcb2ca1a2f8560f0c989`  
Expected JSON SHA-256: `44e851c9333140f5c04e49544d728223d0883d6cd33da65430667723784a202e`

Finish reason: STOP; error category: null.

| Field | Expected | Actual | Comparison |
| --- | --- | --- | --- |
|title|Kolory miasta|Kolory miasta|MATCH|
|date|2027-05-16|2027-05-16|MATCH|
|location|Przykładowa Galeria|Przykładowej Galerii|MISMATCH|
|format|IN\_PERSON|IN\_PERSON|MATCH|
|free|true|true|MATCH|

## registration-date-testdata

The registration deadline is not the event date.

Tags: date, registration. Outcome: **FACT_MISMATCH**; schema accepted: true; findings: 0; elapsed: 381178 ms.

Document SHA-256: `bab875b225615550f9cd8295a1e8a65676975491d5c4d2f8751422ebc79a68ef`  
Expected JSON SHA-256: `334d46a9cdc5b86d2da5ee7c77998f04792f970655a726069b6bfc6c9f7bc78c`

Finish reason: STOP; error category: null.

| Field | Expected | Actual | Comparison |
| --- | --- | --- | --- |
|title|Otwarte dane w praktyce|Otwarte dane w praktyce|MATCH|
|date|2027-06-12|2027-06-12|MATCH|
|location|https://spotkanie.example.org/dane|null|MISMATCH|
|format|ONLINE|ONLINE|MATCH|
|free|true|true|MATCH|

## cross-year-testdata

An explicit year is preserved even at a calendar boundary.

Tags: date, in-person. Outcome: **PROVIDER_ERROR**; schema accepted: null; findings: null; elapsed: 601210 ms.

Document SHA-256: `9615ee6b5c58414d4ca734c2a3351526f49a327ccced7dad31bdb02b9101ad22`  
Expected JSON SHA-256: `355e15dc6f6d041d7ad1948a3656ee73b7113acb15f66f700b8c66e5944d41ec`

Finish reason: null; error category: OpenAIInvalidDataException.

| Field | Expected | Actual | Comparison |
| --- | --- | --- | --- |
|title|Ostatnia mapa roku|unavailable|UNAVAILABLE|
|date|2026-12-31|unavailable|UNAVAILABLE|
|location|Przykładowe Obserwatorium|unavailable|UNAVAILABLE|
|format|IN\_PERSON|unavailable|UNAVAILABLE|
|free|false|unavailable|UNAVAILABLE|

## leap-day-testdata

The stated leap day is valid in 2028.

Tags: date, leap-year. Outcome: **TRUNCATED**; schema accepted: null; findings: null; elapsed: 584720 ms.

Document SHA-256: `247a8d52d32952caca5ea91dea19141d79e888b6e0fc37d3bfb07de333dbb154`  
Expected JSON SHA-256: `2203cc1468746ef6cf34f1b9f2ddaf4d99e3c35ec2f5c294457a1258c752bfbc`

Finish reason: LENGTH; error category: IllegalStateException.

| Field | Expected | Actual | Comparison |
| --- | --- | --- | --- |
|title|Dodatkowy dzień na pomysły|unavailable|UNAVAILABLE|
|date|2028-02-29|unavailable|UNAVAILABLE|
|location|https://spotkanie.example.org/luty|unavailable|UNAVAILABLE|
|format|ONLINE|unavailable|UNAVAILABLE|
|free|true|unavailable|UNAVAILABLE|

## no-year-testdata

A missing year must not be filled from the system clock.

Tags: missing, date. Outcome: **FACT_MISMATCH**; schema accepted: true; findings: 0; elapsed: 401822 ms.

Document SHA-256: `2fcd5a872d75fbcc15297772a96ca637879fd669ad6d87d707625cdab0aa020e`  
Expected JSON SHA-256: `6ab16b52b569db0873710ee4d2305c4f195f553dcdd278ac7132e4abc20a61e2`

Finish reason: STOP; error category: null.

| Field | Expected | Actual | Comparison |
| --- | --- | --- | --- |
|title|Marcowe szkice|Marcowe szkice|MATCH|
|date|null|null|MATCH|
|location|Przykładowa Szkicownia|Przykładowej Szkicowni|MISMATCH|
|format|IN\_PERSON|IN\_PERSON|MATCH|
|free|true|true|MATCH|

## multi-day-testdata

The single-date contract cannot represent a two-day event; no unique event day is stated.

Tags: ambiguous, date-range. Outcome: **PROVIDER_ERROR**; schema accepted: null; findings: null; elapsed: 600800 ms.

Document SHA-256: `bd202e329e37a941fced0322a1c32baf02a3bba7f73ee62c19284e8bad10481a`  
Expected JSON SHA-256: `3de9da4bb5a4baffd1625c2fad2a839415aef36aacc31bbf793633ac789527ab`

Finish reason: null; error category: OpenAIIoException.

| Field | Expected | Actual | Comparison |
| --- | --- | --- | --- |
|title|Dwa dni z makietą|unavailable|UNAVAILABLE|
|date|null|unavailable|UNAVAILABLE|
|location|Przykładowa Modelarnia|unavailable|UNAVAILABLE|
|format|IN\_PERSON|unavailable|UNAVAILABLE|
|free|false|unavailable|UNAVAILABLE|

## free-registration-testdata

Mandatory registration does not make explicitly free admission paid.

Tags: registration, free. Outcome: **FACT_MISMATCH**; schema accepted: true; findings: 0; elapsed: 506381 ms.

Document SHA-256: `f9a044509c6270af820c4183a3192dc2cd7ff2e25217f2dd9521d1330e9f25bb`  
Expected JSON SHA-256: `ba42981fc22bcfce7e0ccac21ad366113a49f7c8b65572f6ef098e75e9afe53e`

Finish reason: STOP; error category: null.

| Field | Expected | Actual | Comparison |
| --- | --- | --- | --- |
|title|Rezerwacja miejsca przy stole|Rezerwacja miejsca przy stole|MATCH|
|date|2027-07-08|2027-07-08|MATCH|
|location|Przykładowa Czytelnia|Przykładowej Czytelni|MISMATCH|
|format|IN\_PERSON|IN\_PERSON|MATCH|
|free|true|true|MATCH|

## optional-donation-testdata

An optional donation does not override explicitly free entry.

Tags: admission, free. Outcome: **FACT_MISMATCH**; schema accepted: true; findings: 0; elapsed: 319835 ms.

Document SHA-256: `78155aad6fb073eec47d04afd79dd5fc9a2fb8ead79290eddcb18b5f746cc3ab`  
Expected JSON SHA-256: `ffa8af31de828f7b6bbe43885dff019b1a1bc36718d01a6509e132465d980113`

Finish reason: STOP; error category: null.

| Field | Expected | Actual | Comparison |
| --- | --- | --- | --- |
|title|Wymiana roślin z papieru|Wymiana roślin z papieru|MATCH|
|date|2027-08-22|2027-08-22|MATCH|
|location|Przykładowy Ogród|Przykładowym Ogrodzie|MISMATCH|
|format|IN\_PERSON|IN\_PERSON|MATCH|
|free|true|true|MATCH|

## deposit-paid-testdata

A booking deposit is part of an explicitly paid ticket.

Tags: admission, paid. Outcome: **FACT_MISMATCH**; schema accepted: true; findings: 0; elapsed: 290409 ms.

Document SHA-256: `699fea07fe37047b2718a00741ead539cd9d8a906de42e06e6dfb379ce2cb223`  
Expected JSON SHA-256: `5c8ce43764e6c098ed0b933541faf09bd1d666d12ad04aa877b364c063731953`

Finish reason: STOP; error category: null.

| Field | Expected | Actual | Comparison |
| --- | --- | --- | --- |
|title|Ceramiczna mozaika|Ceramiczna mozaika|MATCH|
|date|2027-09-14|2027-09-14|MATCH|
|location|Przykładowa Pracownia Gliny|Przykładowej Pracowni Gliny|MISMATCH|
|format|IN\_PERSON|IN\_PERSON|MATCH|
|free|false|false|MATCH|

## mixed-admission-testdata

One boolean cannot represent free streaming and paid room admission, so admission remains unknown.

Tags: hybrid, ambiguous, admission. Outcome: **FACT_MISMATCH**; schema accepted: true; findings: 0; elapsed: 569310 ms.

Document SHA-256: `67a150ef76f36d36abdad6ef226b01b3b8ecd0137a8994c93d56cb988fc2898b`  
Expected JSON SHA-256: `1c247194c2cd0ace98bee8b6dc975626fa1d42d924012d2a3a567eea9df6fc85`

Finish reason: STOP; error category: null.

| Field | Expected | Actual | Comparison |
| --- | --- | --- | --- |
|title|Dwa sposoby oglądania map|Dwa sposoby oglądania map|MATCH|
|date|2027-10-07|2027-10-07|MATCH|
|location|Przykładowa Sala Map oraz https://wydarzenia.example.org/dwie-mapy|Przykładowa Sala Map oraz https://wydarzenia.example.org/dwie-mapy|MATCH|
|format|HYBRID|HYBRID|MATCH|
|free|null|false|MISMATCH|

## quoted-instructions-testdata

Quoted instructions in the announcement are source data and must not override event facts.

Tags: quoted-text, in-person. Outcome: **PROVIDER_ERROR**; schema accepted: null; findings: null; elapsed: 600430 ms.

Document SHA-256: `29cc0e5b717f5b6e3475d9ece2eab29a0653490bd13d05491298adcdf9aaae7e`  
Expected JSON SHA-256: `e2b2c07a6396dbd05a3d81d0a9d9814336f1fd7039ce48c0c819db5eb9896ff5`

Finish reason: null; error category: OpenAIIoException.

| Field | Expected | Actual | Comparison |
| --- | --- | --- | --- |
|title|Cytaty na plakacie|unavailable|UNAVAILABLE|
|date|2027-11-04|unavailable|UNAVAILABLE|
|location|Przykładowa Drukarnia|unavailable|UNAVAILABLE|
|format|IN\_PERSON|unavailable|UNAVAILABLE|
|free|true|unavailable|UNAVAILABLE|

## json-braces-testdata

Braces belong to the stated title and must survive template rendering unchanged.

Tags: literal-text, missing. Outcome: **FACT_MISMATCH**; schema accepted: true; findings: 0; elapsed: 255438 ms.

Document SHA-256: `ba1150328be70238838e736a407c8a1a42818de870c5ae92794bc724b0922c2e`  
Expected JSON SHA-256: `04bb562468bc079e2575f80a94a7c16b5bcafdc700701a865c379bad058759b4`

Finish reason: STOP; error category: null.

| Field | Expected | Actual | Comparison |
| --- | --- | --- | --- |
|title|Warsztat {mapy}|Warsztat {mapy}|MATCH|
|date|2027-12-11|2027-12-11|MATCH|
|location|https://spotkanie.example.org/nawiasy|null|MISMATCH|
|format|ONLINE|ONLINE|MATCH|
|free|null|null|MATCH|

## online-no-url-testdata

Online format does not supply an unstated URL.

Tags: online, missing, location. Outcome: **PASSED**; schema accepted: true; findings: 0; elapsed: 161678 ms.

Document SHA-256: `1e0c31978a372fa4d3ce1a915827e60cffa4cacac22e33e7e22cb16eb7a0c7a1`  
Expected JSON SHA-256: `f492cd45d7ede5bb02afef1d4e8bf3e3f29aaa9202bdc80bd508436db78e3a91`

Finish reason: STOP; error category: null.

| Field | Expected | Actual | Comparison |
| --- | --- | --- | --- |
|title|Spotkanie bez opublikowanego linku|Spotkanie bez opublikowanego linku|MATCH|
|date|2028-01-19|2028-01-19|MATCH|
|location|null|null|MATCH|
|format|ONLINE|ONLINE|MATCH|
|free|true|true|MATCH|

## venue-named-online-testdata

A venue named Online is still in-person when the format is explicit.

Tags: in-person, literal-text. Outcome: **PASSED**; schema accepted: true; findings: 0; elapsed: 339607 ms.

Document SHA-256: `a0a0ede5605434a44e553d15d88b7c3075e02e7286ca6e553079d18119fb77f0`  
Expected JSON SHA-256: `fc06e2c36258d4a7201bc5aaa2500d5810692d7da3b8792bc6465339758db1ef`

Finish reason: STOP; error category: null.

| Field | Expected | Actual | Comparison |
| --- | --- | --- | --- |
|title|Szyldy i ich znaczenie|Szyldy i ich znaczenie|MATCH|
|date|2028-02-23|2028-02-23|MATCH|
|location|Przykładowa sala Online|Przykładowa sala Online|MATCH|
|format|IN\_PERSON|IN\_PERSON|MATCH|
|free|false|false|MATCH|

## registration-not-admission-testdata

Free registration says nothing about the price of entering the event.

Tags: registration, missing, admission. Outcome: **FACT_MISMATCH**; schema accepted: true; findings: 0; elapsed: 459116 ms.

Document SHA-256: `f0462043443271a6c1b377a0a40a674a7c3baf1c6538581b46e0d38e62eeaacb`  
Expected JSON SHA-256: `f6de326f5ca18b23a9d93b4640e267fc0ee60a254d842abb499107e7fccbeb69`

Finish reason: STOP; error category: null.

| Field | Expected | Actual | Comparison |
| --- | --- | --- | --- |
|title|Formularze bez pośpiechu|Formularze bez pośpiechu|MATCH|
|date|2028-03-15|2028-03-15|MATCH|
|location|Przykładowe Biuro Pomysłów|Przykładowym Biurze Pomysłów|MISMATCH|
|format|IN\_PERSON|IN\_PERSON|MATCH|
|free|null|null|MATCH|

## ambiguous-format-testdata

Alternative formats are not a confirmed hybrid event; neither a venue nor URL is provided.

Tags: ambiguous, format, location. Outcome: **PASSED**; schema accepted: true; findings: 0; elapsed: 279614 ms.

Document SHA-256: `b5a30d501e60473a3c821ead8921bdc22166d1dfe4978319a4ba48d9dc060241`  
Expected JSON SHA-256: `a11442da910560d73fabffc350994ae540eea6ef2e37baae2135c4d6871c30de`

Finish reason: STOP; error category: null.

| Field | Expected | Actual | Comparison |
| --- | --- | --- | --- |
|title|Wybierzemy sposób spotkania|Wybierzemy sposób spotkania|MATCH|
|date|2028-04-20|2028-04-20|MATCH|
|location|null|null|MATCH|
|format|null|null|MATCH|
|free|true|true|MATCH|

## missing-title-testdata

The document explicitly gives no event title; a generic description must not become an invented title.

Tags: missing, title. Outcome: **PASSED**; schema accepted: true; findings: 0; elapsed: 231155 ms.

Document SHA-256: `b86e7a6d3590530a49f7e3c7938b84e75692fc621deef42babc6872c6751d2c8`  
Expected JSON SHA-256: `e76668e8326618c82f55b789631e74560bf3f1cb1575d1d6e4efd639c8f91b39`

Finish reason: STOP; error category: null.

| Field | Expected | Actual | Comparison |
| --- | --- | --- | --- |
|title|null|null|MATCH|
|date|2028-05-30|2028-05-30|MATCH|
|location|Przykładowy Pawilon|Przykładowy Pawilon|MATCH|
|format|IN\_PERSON|IN\_PERSON|MATCH|
|free|true|true|MATCH|
