# Promptjig — raport etapu 0

Data: 2026-11-10. **Status: etap 0 zakończony.** P4 potwierdzone dla poniższego
digestu i backendu; przed etapem 3 potrzebna korekta przykładu renderowania.

## Zakres i materiał

Osobny projekt `gradle-plugins/promptjig`, bez zmian w `tichodroma-svc` i jego
worktree E1. Spike obejmuje schemat formatu `promptContract: 1`, wczytanie YAML,
odczyt parametrów ST4, odcisk, sprawdzenie wskaźników walidatora i transport P4.
Nie implementuje pluginów, blokady, generatora ani biblioteki wykonawczej.

Materiał źródłowy: niezacommitowany zasób
`worktrees/tichodroma-svc-ai-audit-e1/src/main/resources/prompts/patient/disability/certificate-extraction.st`,
HEAD worktree `85da8cffd53b378f5eea451221093d8a340abea7` (sam zasób nie jest częścią
tego commita). Kopia bajt w bajt znajduje się w zasobach spike'a pod `prompts/e1`.
SHA-256 źródła:
`fe6c80973f0d0a075bcf307c16c2826e4bd1af4776a03c84a676dca83105f0fa`.

Przykład w analizie opisuje część pól. Pilotażowy kontrakt obejmuje wszystkie 15
pól obecnego ekstraktora. `symbolCodes` ma wzorzec i zamknięty enum, a
`indicationCodes` enum I–IX, zgodnie z obecnym mapowaniem domenowym. Sam wzorzec
`^\d{2}-[A-Z]$` z analizy przepuszczałby wartości nieobsługiwane przez domenę.
Kontrakt nie odwołuje się do klas domenowych. Nie badano jakości ekstrakcji.

## Środowisko

- JDK 25.0.1, kompilacja `--release 17`, Gradle Wrapper 9.8.2 z sumą SHA-256.
- Jackson 2.22.4, networknt 2.0.8, ST4 4.3.4, JUnit 5.14.4.
- Spring AI 2.0.2 wyłącznie w źródłach spike'a; rdzeń bez zależności od Springa.
- Docker Model Runner 1.2.9, Docker Engine 29.9.3, backend llama.cpp b9879-cpu
  (`72874f5`), Intel Core Ultra 9 285H.
- Zainstalowano na polecenie użytkownika `ai/qwen3.5:9b-q4_K_XL` (6,88 GB).
  Digest wywołań:
  `sha256:f799fb1ad223f00fc55cf4d6c12de7dc9b71d10ca427bb0ff7b644f49d9a7960`.

## Parametry i renderowanie ST4

Odczyt AST działa dla zwykłych zmiennych, właściwości `contract.output.enums`,
warunków (także niewykonanej gałęzi), funkcji ST4, komentarzy, właściwości
dynamicznych i anonimowych podszablonów z lokalnymi nazwami i indeksami. Stały
szablon ma pusty zbiór parametrów. Błędna składnia jest odrzucana. Wywołania
zewnętrznych szablonów są w prototypie jawnie odrzucane; nie są częścią pilotażu.
Adapter korzysta z wewnętrznych klas `STParser` i `CompiledST`, dlatego wymaga
przypiętej wersji i utrzymania testów przy aktualizacjach ST4.

Szablon E1 używa tylko `ocrText`. Rozdzielone szablony pilotażu używają
odpowiednio `contract` i `ocrText`; łączne sprawdzenie przechodzi. Testy wykrywają
nieznane, nieużyte i zarezerwowane nazwy. `contract` może być nieużyty.

**Wykryty rozjazd:** przykład
`{contract.output.enums.certificateType; separator=", "}` nie przechodzi
`StTemplateRenderer` 2.0.2 w trybie `THROW`: renderer żąda zmiennej `separator`.
Nie jest to parametr wejścia ani problem odczytu AST. Wersja Spring AI skanuje
tokeny przy walidacji i kwalifikuje nazwę opcji jako zmienną.

Oddzielny kandydat `certificate-extraction.compatible.st` używa
`{contract.output.enumText.certificateType}` oraz `contract.output.fieldText`.
Zawartość enumów pochodzi ze schematu i jest jedynie formatowana do prezentacji;
instrukcje pozostają w `.st`. Ten wariant przechodzi `SystemPromptTemplate` z
zasobem i `THROW`, a test sprawdza obecność rzeczywistych wartości enumów.
To propozycja korekty API parametru `contract` przed etapem 3, bez zmiany
zatwierdzonego formatu kontraktu ani wyłączania walidacji.

Próba anonimowego podszablonu ujawniła analogiczny problem renderera: żąda
lokalnej nazwy `value` i opcji `separator` jako parametrów globalnych. AST
poprawnie wyklucza obie nazwy. Rozszerzenie składni obsługiwanej przez rdzeń
nie oznacza więc automatycznie zgodności z walidacją Spring AI 2.0.2.

Wniosek: wybrać AST do weryfikacji buildowej. Renderowanie próbne nie zastępuje
odczytu wszystkich gałęzi, a walidacja tokenów Spring AI nie jest równoważna AST.

## Wskaźniki walidatora i odczyt odpowiedzi

Wymagane są `PathType.JSON_POINTER`, `formatAssertionsEnabled(true)` oraz
wyłączone pobieranie schematów z sieci. Draft 2020-12 nie zapewnia domyślnie
sprawdzania `format` jako asercji. Próba z `2026-02-30` wykazuje różnicę.

| Przypadek | Wskaźnik instancji | Dodatkowe dane |
| --- | --- | --- |
| Nieznany enum | `/degree` | reguła `enum` |
| Niepoprawna data | `/issuedAt` | reguła `format` |
| Zły typ liścia | `/permanent` | reguła `type` |
| Błędne elementy listy | `/symbolCodes/0`, `/symbolCodes/2` | wiele naruszeń na jednym elemencie |
| Brak wymaganego pola | pusty wskaźnik korzenia | `getProperty() = patientSurname` |
| Nieznany klucz | pusty wskaźnik korzenia | `getProperty() = unknown/key~name` |
| Zły typ całej tablicy | `/symbolCodes` | błąd struktury |
| Błędne pole przez `$ref` | `/a~1b~0c/0/degree` | lokalizacja schematu prowadzi do `$defs` |
| Brak pola w obiekcie listy | `/a~1b~0c/1` | `getProperty() = degree` |

Wskaźniki wystarczają do realizacji §5.6. Dla `required` i `additionalProperties`
należy dopisać segment z `getProperty()` z escapingiem JSON Pointer. Nie wolno
parsować tekstowego komunikatu błędu. Schemat liścia ustala się z lokalizacji
schematu lub przez rozwiązanie `$ref`, a nie przez ślepe dodanie wskaźnika
instancji do schematu.

Przyszły mechanizm naprawy musi deduplikować cele (np. `pattern` i `enum` na tym
samym elemencie) i usuwać elementy tablic w malejącej kolejności indeksów.
Przed deserializacją trzeba ponownie zwalidować naprawione drzewo. Testy etapu 3
powinny objąć uzupełnianie brakujących nullable i tablic, tryb ścisły oraz błędy
strukturalne; pełny mechanizm naprawy nie jest wynikiem tego spike'a.

Raport zapisuje reguły i lokalizacje, bez wartości pól i tekstów błędów
walidatora. Nazwy nieznanych kluczy także są danymi wejściowymi: w docelowym
audycie nie można bezwarunkowo zapisywać dowolnego wskaźnika zawierającego taki
klucz. Próbki zawierają wyłącznie syntetyczne nazwy.

## Odcisk i format

Odcisk pilotażowego kontraktu:
`sha256:e92be038aef9799dc8818211a16010f270d376b6d718e119d1908149d387484b`.

Wejście SHA-256: ramka identyfikatora `promptjig-fingerprint-v1`, ramka JSON z
rekurencyjnie posortowanymi kluczami, następnie dla każdego szablonu (nazwy
leksykograficznie): ramka nazwy i ramka UTF-8 treści po normalizacji CRLF/CR → LF.
Ramka to 8-bajtowa długość big-endian i bajty wartości. Kolejność wiadomości i
tablic schematu jest zachowana. Ramkowanie zapobiega niejednoznacznej
konkatenacji. To konkretna propozycja do zamrożenia przed pierwszą blokadą.

Testy potwierdzają identyczny odcisk przy zmianie kolejności kluczy i LF/CRLF,
oraz różny przy zmianie instrukcji, schematu lub kolejności wiadomości. Nie jest
to implementacja pełnego JSON Canonicalization Scheme ani semantycznego
porównywania dowolnych schematów.

Schemat formatu odrzuca opcje modelu, nieznane klucze, zewnętrzne `$ref`,
`oneOf` w kontrakcie i wyjścia poza katalog szablonów. Wczytanie odrzuca
duplikaty kluczy, wiele dokumentów YAML i nieprawidłowy UTF-8 szablonów.
To podstawa etapu 1, nie kompletny zbiór reguł i diagnostyk `plik:wiersz:kolumna`.

## P4 — próba transportu

Pierwsze uruchomienie z limitem 120 s przekroczyło czas podczas zimnego
ładowania Qwen. Model był nadal w stanie `Loading...`; nie jest to dowód
braku obsługi `json_schema`. Po `docker model run --detach` powtórzono próbę z
limitem 600 s na żądanie. Nie zmieniano konfiguracji modelu ani backendu.
Końcowy wynik jest w `core/build/reports/stage0/model-runner.json` i zachowanej
kopii `docs/evidence/model-runner.json`. Zimne timeouty zachowano osobno w
`docs/evidence/model-runner-cold-timeouts.json`.

| Przypadek | HTTP | Wynik walidacji | Czas żądania |
| --- | --- | --- | --- |
| Orzeczenie, `json_object` | 200 | zgodna odpowiedź | 112,462 s |
| Orzeczenie, `json_schema` | 200 | zgodna odpowiedź | 106,449 s |
| Kontrola enumu, `json_object` | 200 | naruszenie `enum`: `PROMPT_ONLY` | 52,009 s |
| Kontrola enumu, `json_schema` | 200 | dozwolona wartość `CONTRACT_ONLY` | 62,293 s |

**Wniosek P4: TAK** dla `ai/qwen3.5:9b-q4_K_XL` o zapisanym digescie na
llama.cpp b9879-cpu / Docker Model Runner 1.2.9. Można przyjąć transport
`json_schema` w pilotażu po E1. Walidacja odpowiedzi w JVM pozostaje konieczna.
Aktualizacja modelu lub backendu wymaga ponowienia próby.

Są to pojedyncze pomiary transportu, bez oceny jakości ekstrakcji i bez
porównywania wydajności trybów: drugie żądanie orzeczenia korzystało z cache
284 tokenów promptu. Odpowiedź zawierała również osobne `reasoning_content`;
walidacji podlegało `message.content`. Całość końcowego uruchomienia Gradle
z testami trwała 5 min 52 s. Próba offline zajęła 2461 ms (wcześniejsze próbki
916–1152 ms), z inicjalizacją JVM/bibliotek i odczytem plików, bez inferencji.

Test obejmuje pełny schemat orzeczenia i przypadek kontrolny: prompt żąda
`PROMPT_ONLY`, a schemat dopuszcza wyłącznie `CONTRACT_ONLY`. Dla obu porównuje
się `json_object` z `json_schema` w opakowaniu OpenAI
`response_format.json_schema.schema`, z `strict: true`.
Sukces wymaga poprawnego JSON i zgodności z walidatorem po stronie JVM.
Samo HTTP 200 nie wystarcza.

Próba orzeczenia używa instrukcji wariantu `compatible.st`; schemat odpowiedzi
jest bez zmian. Odcisk powyżej dotyczy kontraktu pilotażu z pierwotnym
`system.st`, a nie nieprzyjętej jeszcze propozycji zmiany widoku `contract`.

## Odtworzenie i dalsze prace

Polecenia są w README. Artefakty pomiarowe i syntetyczne żądania/odpowiedzi
powstają w `core/build/reports/stage0/`; wyniki JUnit w
`core/build/test-results/test/`. Model nie jest wywoływany przez `check`.
Trwałe, syntetyczne podsumowania pomiarów są w `docs/evidence/`. Nie wykonano
publikacji pluginu ani integracji z aplikacją.

Weryfikacja końcowa rdzenia: `gradlew.bat check :core:spike` zakończone sukcesem,
22 testy JUnit (9 kontraktu, 9 ST4, 4 walidatora), bez pominiętych testów i bez
wywołania modelu. Oddzielny przebieg `modelSpike` zakończył wszystkie cztery
żądania. Testy narzędzi dokumentacji wiki: 31 testów zakończonych sukcesem.
Testów aplikacji i E1 nie uruchamiano.
Wiki: `mkdocs build --strict` i `scripts/check_docs.py` zakończone sukcesem
(346 stron). Po próbach model wyładowano z pamięci; pozostaje zainstalowany.

Etap 1: pełne diagnostyki i reguły semantyczne, zamrożenie schematu/ramkowania,
blokada i `lock-baseline`, cienki plugin Gradle, TestKit/configuration cache.
Schemat prototypu nie zastępuje wszystkich kontroli semantycznych (np. zgodności
`required` z `properties`, ograniczeń typu i spójności enumów); one także
należą do etapu 1. Agregowanie diagnostyk oraz pozycje w YAML pozostają otwarte.
Etap 2: generator i dokładność mapowania dat/enumów/Jackson 2/3.
Przed etapem 3: korekta przykładu `separator`, ustalenie bezpiecznych wskaźników
audytowych i testy ponownej walidacji drzewa po naprawie.

Źródła pierwotne sprawdzone podczas spike'a:
[kod renderera Spring AI](https://github.com/spring-projects/spring-ai/blob/v2.0.2/spring-ai-template-st/src/main/java/org/springframework/ai/template/st/StTemplateRenderer.java)
(również lokalny JAR sources 2.0.2),
[networknt](https://github.com/networknt/json-schema-validator),
[ST4](https://github.com/antlr/stringtemplate4),
[konfiguracja Docker Model Runner](https://docs.docker.com/ai/model-runner/configuration/).
