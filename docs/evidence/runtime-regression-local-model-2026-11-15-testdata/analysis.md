# Pierwszy pełny pomiar lokalnego modelu — 2026-11-15

Próba `runLocalRegression` zakończyła wszystkie **25 fikcyjnych ogłoszeń**
rewizji 1. Tryb raportu to **`LOCAL_MODEL`**. Ścisłe porównanie zaliczyło
**10/25 przypadków (40%)** i **90/125 pól (72%)**. Odpowiedź nadająca się
do walidacji była dostępna dla 20 przypadków; wszystkie te odpowiedzi
zaakceptowano bez napraw. Pięć pozostałych wywołań nie dostarczyło wyniku.

Gradle zakończył zadanie kodem `1`, ponieważ próg pełnego zaliczenia nie został
osiągnięty. Raporty zapisano przed tym zakończeniem. Pomiar jest kompletny;
nie pominięto ani nie ponowiono żadnego przypadku.

Dowody:

- [Raport JSON](quality-report.json) i [raport Markdown](quality-report.md)
  wygenerowane przez aplikację, zachowane bez korekt wyniku.
- [Analiza każdego niezaliczonego przypadku](error-analysis.json).
- [Kontekst uruchomienia](run-context.json), [środowisko](environment.json)
  i [kontrola spójności raportu](verification.json).

## Tożsamość i ustawienia próby

| Parametr | Wartość |
| --- | --- |
| Commit źródeł | `cc7a70ffed7100503465d332a9232f15063f93d9` |
| Promptjig: plugin i runtime | Opublikowane `0.3.1`; bez lokalnego checkoutu producenta i lokalnego install |
| Piny Spring AI / Jackson Databind w katalogu wersji | `2.0.2` / `3.1.8` |
| Model przed uruchomieniem | `docker.io/ai/qwen3.5:9b-q4_K_XL`, GGUF |
| Digest użyty w żądaniach | `sha256:f799fb1ad223f00fc55cf4d6c12de7dc9b71d10ca427bb0ff7b644f49d9a7960` |
| Endpoint | `http://127.0.0.1:12434/engines/v1` |
| Temperatura / automatyczne ponowienia | `0` / `0` |
| Limit generacji / timeout wywołania | `4096` tokenów / `600 s` |
| Kontrakt | `events.announcement-extraction-v1`, `output.strict: false` |
| Odcisk kontraktu | `sha256:1b9b411a389962e450869bd6b3d982c62a65533700fe42e674ffd283c23d1b7d` |
| Zbiór | Rewizja `1`, 25 przypadków, SHA-256 `9e99f47ab001a16989de44596cdabd97c50819dee2ef00d2b6ecac371ed38690` |
| Początek / koniec procesu | `2026-11-15T04:15:22.0360625Z` / `2026-11-15T07:05:58.3728340Z` |
| Utworzenie raportu | `2026-11-15T07:05:52.124517400Z` |

Jedno niezależne żądanie na ogłoszenie, sekwencyjnie, bez historii rozmowy.
W trakcie próby nie zmieniano promptów, kontraktu, locka, źródeł, wzorców
ani ustawień. `strict: false` dopuszcza naprawianie odpowiedzi, ale naprawiony
przypadek nie otrzymuje pełnego zaliczenia.

Uruchomiono w Windows przez lokalny Docker Model Runner `v1.2.9` w Docker
Desktop; Gradle korzystał z `C:\Develop\JDK\temurin-25.0.1`. Nie ustalano
konfiguracji akceleratora ani offloadu. Nie wykonywano operacji na środowisku
dev `tichodroma-svc` ani nie włączano tam AI.

## Wynik automatyczny

| Wynik przypadku | Liczba |
| --- | ---: |
| Pełne zaliczenie bez napraw | 10 |
| Niezgodność pola ze wzorcem (`FACT_MISMATCH`) | 10 |
| Naprawiona odpowiedź (`REPAIRED`) | 0 |
| Odrzucona przez walidator (`REJECTED`) | 0 |
| Ucięcie generacji (`TRUNCATED`) | 2 |
| Błąd odpowiedzi (`RESPONSE_ERROR`) | 0 |
| Błąd dostawcy/transportu (`PROVIDER_ERROR`) | 3 |

Każda z dziesięciu dostępnych, niezaliczonych odpowiedzi ma dokładnie jedno
niezgodne pole. Pięć niedostępnych odpowiedzi daje 25 pól `UNAVAILABLE`;
nie otrzymują punktów nawet tam, gdzie wzorzec oczekuje `null`.

| Pole | Zgodne / wszystkie | Dostępne | Znane fakty: zgodne / oczekiwane | `null`: zachowane / oczekiwane |
| --- | ---: | ---: | ---: | ---: |
| `title` | 20/25 | 20 | 19/24 | 1/1 |
| `date` | 20/25 | 20 | 17/21 | 3/4 |
| `location` | 11/25 | 20 | 8/21 | 3/4 |
| `format` | 20/25 | 20 | 18/23 | 2/2 |
| `free` | 19/25 | 20 | 15/20 | 4/5 |

W dostępnych odpowiedziach tytuły, daty i formaty były zgodne ze wzorcami.
Model poprawnie wybrał nowy termin po zmianie, odróżnił termin zapisów od
daty wydarzenia i pozostawił datę bez roku jako `null`. Poprawnie rozpoznał
obowiązkowe zapisy przy darmowym wstępie, dobrowolną darowiznę, płatny bilet
z zaliczką i brak informacji o cenie mimo darmowej rejestracji. Zachował
tytuł `Warsztat {mapy}`, nie wymyślił linku, rozpoznał stacjonarną salę
o nazwie „Online”, nieustalony format i brak tytułu.

## Przegląd przyczyn

### Siedem różnic w odmianie nazw miejsc

| Przypadek | Oczekiwane `location` | Otrzymane `location` |
| --- | --- | --- |
| `ambiguous-date-testdata` | Przykładowa Pracownia | Przykładowej Pracowni |
| `postponed-date-testdata` | Przykładowa Galeria | Przykładowej Galerii |
| `no-year-testdata` | Przykładowa Szkicownia | Przykładowej Szkicowni |
| `free-registration-testdata` | Przykładowa Czytelnia | Przykładowej Czytelni |
| `optional-donation-testdata` | Przykładowy Ogród | Przykładowym Ogrodzie |
| `deposit-paid-testdata` | Przykładowa Pracownia Gliny | Przykładowej Pracowni Gliny |
| `registration-not-admission-testdata` | Przykładowe Biuro Pomysłów | Przykładowym Biurze Pomysłów |

Model zachował formę występującą w dokumencie po przyimku „w”, a wzorce
używają mianownika. Przegląd źródeł nie wskazuje na wymyślenie innych miejsc.
Obecny prompt nie nakazuje sprowadzania nazwy do mianownika. Są to rzeczywiste
niezgodności dokładnego porównania oraz ujawniona niejednoznaczność reprezentacji.
Surowy wynik pozostaje bez zmian; nie dopasowano wzorców do modelu i nie
wyliczano „poprawionego” wskaźnika trafności.

### Dwa pominięte linki spotkań online

- `registration-date-testdata`: oczekiwano
  `https://spotkanie.example.org/dane`, otrzymano `null`.
- `json-braces-testdata`: oczekiwano
  `https://spotkanie.example.org/nawiasy`, otrzymano `null`.

Oba linki są jawnie podane w źródłach. To utrata dostępnej informacji względem
przyjętej interpretacji `location`, a nie różnica gramatyczna. Reguła, że adres
spotkania online jest miejscem wydarzenia, występuje we wzorcach i uwagach
zbioru, ale nie jest wprost opisana w instrukcjach. Inne przypadki z linkami
przeszły; wynik nie uzasadnia twierdzenia, że model zawsze pomija URL.

### Jedna rozbieżność przy mieszanej odpłatności

`mixed-admission-testdata`: wzorzec `free: null`, model `free: false`.
Dokument opisuje bezpłatną transmisję i płatny bilet do sali. Prompt definiuje
`true` dla jawnie darmowego wstępu i `false` dla jawnie płatnego, lecz nie
ustala reguły dla różnych cen zależnych od sposobu udziału. `false` ma oparcie
w płatnej opcji z dokumentu. Wzorzec używa `null`, bo pojedynczy boolean nie
oddaje obu cen. Przed zmianą promptu trzeba jawnie uzgodnić tę politykę;
ta próba zachowuje niezaliczenie. Pozostałe cztery pola są zgodne.

### Dwa wyczerpania budżetu generacji

| Przypadek | Czas | `finish_reason` | Tokeny generacji | Znaki odpowiedzi |
| --- | ---: | --- | ---: | ---: |
| `conflicting-place-testdata` | 544,396 s | `LENGTH` | 4096 | 0 |
| `leap-day-testdata` | 584,720 s | `LENGTH` | 4096 | 0 |

Potwierdzona przyczyna braku wyniku: zakończenie generacji na limicie tokenów.
Nie powstała treść do walidacji. Backend nie raportuje osobnego licznika
rozumowania; nie można ustalić jego udziału ani uznać wszystkich tokenów za
widoczny JSON. Nie da się ocenić wyboru miejsca w pierwszym przypadku ani
interpretacji 29 lutego w drugim.

### Trzy błędy dostawcy w pobliżu timeoutu

| Przypadek | Czas | Zapisana klasa błędu |
| --- | ---: | --- |
| `cross-year-testdata` | 601,210 s | `OpenAIInvalidDataException` |
| `multi-day-testdata` | 600,800 s | `OpenAIIoException` |
| `quoted-instructions-testdata` | 600,430 s | `OpenAIIoException` |

Czasy są zgodne z hipotezą zadziałania timeoutu 600 s. Zapisany raport nie
zawiera jednak przyczyn wyjątków, komunikatów transportu ani surowych odpowiedzi,
więc nie potwierdza dokładnej przyczyny tych trzech awarii. `finish_reason`
i liczniki są nieznane. Nie przypisano tym przypadkom błędów rzeczowych.

W szczególności próba nie rozstrzyga odporności na polecenie cytowane w
ogłoszeniu. Nie potwierdza też zachowania dla wydarzenia dwudniowego. Jego
wzorzec oczekuje `date: null`, ponieważ kontrakt ma pojedynczą datę, ale prompt
nie wyjaśnia tej reguły — warto ją doprecyzować przy przyszłym przeglądzie.

## Tokeny, czas i ograniczenia

- `finish_reason`: `STOP` — 20; `LENGTH` — 2; nieznany — 3.
- Zgłoszone liczniki z **22/25 wywołań**: prompt `4539`, generacja `51156`,
  łącznie `55695`. To częściowa suma; zużycie trzech błędnych wywołań jest
  nieznane. Oddzielne tokeny rozumowania: brak danych we wszystkich przypadkach.
- Suma czasów wywołań: **2 h 49 min 45,311 s**; mediana **386,195 s**;
  minimum **161,678 s**, maksimum **601,210 s**. Gradle: **2 h 50 min 33 s**.
- Aktywny komputer Windows: Core Ultra 9 285H, około 31,4 GiB RAM. Pojedynczy
  odczyt o `05:41:37Z` wykazał około 838 MiB wolnej pamięci. To ograniczenie
  interpretacji czasu, bez dowodu związku przyczynowego z awariami. Nie był
  to kontrolowany benchmark sprzętu.
- Jedna próba, jeden digest, 25 fikcyjnych dokumentów, bez powtórzeń i bez
  zapisanego seeda. Wyniku nie należy uogólniać na inne dokumenty ani traktować
  jako oszacowania ogólnej trafności modelu.

## Kontrola i odtworzenie

Niezależnie przeliczono liczbę i kolejność przypadków, odciski źródeł i wzorców,
digest całego zbioru (z normalizacją LF i ramkowaniem długości jak w loaderze),
porównania pól, wyniki zbiorcze i liczniki tokenów. Sprawdzono zgodność wejść
pilota i polityk weryfikacji zależności z commitem źródłowym. Kontrola przeszła.

SHA-256 niezmienionego `quality-report.json`:
`5b6b5b9c38b2e3f474b0181095ed01f9d46c2f901fb408e7e2fbac724b154392`.

Rzeczywiste uruchomienie, z katalogu głównego Promptjig, po ustawieniu `JAVA_HOME`:

```powershell
$env:PROMPTJIG_MODEL = 'sha256:f799fb1ad223f00fc55cf4d6c12de7dc9b71d10ca427bb0ff7b644f49d9a7960'
$env:PROMPTJIG_BASE_URL = 'http://127.0.0.1:12434/engines/v1'
$env:PROMPTJIG_TIMEOUT_SECONDS = '600'
$env:PROMPTJIG_MAX_TOKENS = '4096'
.\gradlew.bat -p examples/runtime runLocalRegression `
  '-PpilotReportDir=C:\Develop\Projekty\ngo-patients\gradle-plugins\promptjig\docs\evidence\runtime-regression-local-model-2026-11-15-testdata' `
  --offline --dependency-verification=strict `
  --configuration-cache --configuration-cache-problems=fail `
  --console=plain --max-workers=2
```

Tryb offline wykorzystał wcześniej zweryfikowane pobrania; natywna ścisła
weryfikacja Gradle pozostała włączona. Nie zmieniano zaufanych sum. Przy kolejnym
pomiarze należy wskazać **nowy katalog raportu**, aby nie nadpisać tej próby.
Powyższy zapis służy odtworzeniu konfiguracji i sam nie uruchamia kolejnej próby.

## Następny kontrolowany eksperyment

1. Najpierw zdiagnozować brak dostępnych odpowiedzi: potwierdzić przyczyny
   błędów transportu przy użyciu bezpiecznej kategorii błędu i skorelowanych
   czasów runnera, bez zapisywania surowych odpowiedzi lub rozumowania.
   Następnie wybrać jedną obsługiwaną zmianę ustawienia generacji i porównać
   cały zbiór przy tym samym digescie oraz kontrakcie. Sam wzrost limitu
   tokenów może nie pomóc przy obecnym timeoutcie i czasach wykonania.
2. Uzgodnić reprezentację nazw miejsc, mapowanie URL do `location` i mieszaną
   odpłatność. Zmiana instrukcji wymaga nowej wersji kontraktu i locka;
   ewentualna zmiana wzorców — osobnej rewizji zbioru po przeglądzie źródeł.
3. Porównywać dostępność, pełne zaliczenia, pola, naprawy, tokeny i czas
   równolegle. Zachować tę próbę jako zamrożony punkt odniesienia. Nie zmieniać
   jednocześnie modelu, promptu i limitów, ponieważ wynik nie wskaże przyczyny
   poprawy lub pogorszenia.

Ta próba realizuje pierwszy pomiar i przegląd błędów etapu 5. Kontrolowane
porównania pozostają kolejnym zadaniem; odbiór funkcjonalny na dev nadal czeka
na zakończenie migracji serwera.
