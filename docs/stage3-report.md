# Etap 3 — biblioteka runtime, pilotaż wstrzymany

Stan lokalny z 2026-11-11. Biblioteka `promptjig-spring-ai` realizuje §5.6
analizy. Etap 3 jako całość pozostaje otwarty: użytkownik wstrzymał pilotaż
`tichodroma-svc`, ponieważ usługa obecnie się nie kompiluje.

## Zakres biblioteki

- Rejestr ładuje kontrakty i względne szablony UTF-8 z classpath, także z JAR-ów.
  Walidacja wykorzystuje ten sam rdzeń co plugin. Niepoprawny zasób albo
  powtórzony ID uniemożliwia utworzenie rejestru. Aplikacja tworzy go w eager
  beanie startowym; biblioteka nie narzuca konfiguracji Spring Boot.
- `PromptRuntime.prompt` waliduje wejście, renderuje zasoby przez Spring AI i
  dostarcza enumy/pola z kontraktu. Nie zmienia opcji modelu. Kontrola AST ST4
  poprzedza renderowanie z wyłączoną błędną kontrolą leksykalną Spring AI 2.0.2.
- `read` odrzuca błędny JSON/strukturę, naprawia dozwolone pola i usuwa błędne
  elementy list w trybie domyślnym. `output.strict: true` odrzuca każde naruszenie.
  Po naprawie wykonuje pełną walidację i ścisłe wiązanie do wygenerowanego modelu.
- Dostępne są niezależne bindingi Jackson 2/3, z obsługą dat, zachowaniem offsetów
  i precyzji liczb. Naruszenia nie zawierają wartości ani nieznanych nazw kluczy;
  indeksy tablic odnoszą się do oryginalnej odpowiedzi. Odrzucony wynik nie
  udostępnia częściowo naprawionego drzewa ani wyjątku parsera/bindingu.
- `responseFormat` i generator korzystają ze wspólnej kanonizacji schematu:
  wynik odpowiada dokładnie `OUTPUT_SCHEMA_JSON`. `versionRef` odpowiada
  wygenerowanemu `VERSION_REF`. Rejestr udostępnia odcisk i sensitivity.
- Nowe modele/API używają Lomboka, w tym `@Builder` i `@NonNull`. Artefakty
  Maven zawierają źródła, Javadoc i MIT; oba BOM-y Jacksona są jawne w POM
  runtime, także dla konsumentów bez metadanych Gradle.

Decyzja sensitivity: metadane, bez automatycznego audytu lub autoryzacji modelu.
Audyt, lista dozwolonych modeli i polityka danych należą do aplikacji. Biblioteka
nie zna `document.audit`, nie wywołuje modelu i nie zapisuje danych.

Instrukcja API i granice odpowiedzialności: [Spring AI](spring-ai.md).

## Weryfikacja

Testy obejmują rzeczywistą generację i kompilację modeli `--release 17` dla
obu rodzin Jacksona, odczyt enumów/dat/liczb, zasoby JAR, błędy startu,
usuwanie całych niepoprawnych elementów tablic, schematy rekurencyjne,
współbieżne wywołania oraz diagnostykę bez wartości.

Weryfikacja na JDK 17.0.21:

```powershell
.\gradlew.bat :core:test :core:javadoc :spring-ai:check :spring-ai:assemble :spring-ai:publishAllPublicationsToStagingRepository --console=plain
```

Dodano job CI uruchamiający moduły produkcyjne i ich dokumentację na JDK 17.
Pełny build autorski wymaga JDK 21+ z powodu istniejącego spike; podstawowy
workflow sprawdza go na JDK 25. Dokładne wyniki i zakres wykonanych komend
są w [dowodach](evidence/stage3/summary.json).

Końcowy build na JDK 25.0.1 / Gradle 9.9.1: `check assemble :core:spike
:gradle-plugin:publishPlugins --validate-only` oraz lokalny staging core/runtime
zakończone sukcesem w 3 min 27 s. Przeszło 101 testów rdzenia, 12 runtime i
19 scenariuszy TestKit (Gradle 9.9.1 / JDK 25). Pozostałych kombinacji macierzy
pluginu nie powtarzano; JDK 17 sprawdzono dla rdzenia i runtime, jak wyżej.

Nie przeprowadzono inferencji, wdrożenia ani publikacji zdalnej. Lokalne zadania
staging nie oznaczają dostępności artefaktów w Maven Central/Plugin Portal.

## Uzupełnienie komentarzy i testów

Rozszerzono Javadoc o warunki współbieżnego użycia własnych bindingów, wymaganie
audytu przed odczytem, kolejność walidacji i deserializacji, brak kontroli historii
locka przy starcie oraz granice ochrony danych w wynikach. Komentarze algorytmu
wyjaśniają usuwanie całego elementu tablicy, oryginalne indeksy, kontrolę enumu
przy naprawie do null i końcową walidację naprawionego drzewa.

Dodano 26 przypadków testowych. Obejmują warunki i zmienne lokalne ST4, brak
opcjonalnych parametrów, niezmienność snapshotu po zmianie plików, błędne UTF-8
szablonu, zachowanie rejestru i własnych kodeków, pomijanie bindingu po odrzuceniu
schematu, zachowanie wcześniejszych naruszeń przy błędzie bindingu oraz bezpieczne
podsumowania typów/długości. Sprawdzono też końcowe odrzucenie niedozwolonej
naprawy i stabilną kolejność wszystkich naruszeń mimo błędu struktury.

Nowy test regresyjny wykrył pomijanie enumów elementów tablic z typem
`[array, null]`. Poprawiono odczyt konkretnego typu; działają również aliasy
referencji i referencje w `items`.

Końcowy wynik tego uzupełnienia: **111 testów rdzenia i 28 testów runtime**,
Javadoc z `-Werror` i pakowanie, na JDK 17.0.21 oraz 25.0.1. Zadania:
`:core:test :core:javadoc :spring-ai:check :spring-ai:assemble`. TestKit/macierz
pluginu nie były ponawiane. Dowody:
[comments-and-tests.json](evidence/stage3/comments-and-tests.json).
Pilotaż usługi pozostaje wstrzymany; nie zmieniano ani nie budowano usługi.

## Uzupełnienie po ostrzeżeniach IDE

Poprawiono notację zależności BOM oraz kontrakty nullowalności parametrów,
wyniku kodeka i opcjonalnych atrybutów. Atrybuty trafiają przez interfejs
`TemplateRenderer`, który jawnie dopuszcza wartości null, przy zachowaniu
ładowania i renderowania zasobów przez `PromptTemplate`/`SystemPromptTemplate`.
Drzewo odpowiedzi jest sprawdzane przed wiązaniem. Deklaracje wyjątków
odpowiadają teraz różnicy między Jacksonem 2 i 3.

Testy wymagają obecności tekstu wiadomości i zaakceptowanego drzewa przed ich
odczytem. Executor ma adapter `AutoCloseable` korzystający z API dostępnego
na Javie 17. Uzupełniono adnotacje klas i parametrów nadpisujących metody.

Referencje w fixture pozostają lokalne względem `output.schema`; nie zmieniono
semantyki danych schematu. Po ponownym zgłoszeniu ostrzeżeń ustalono dokładne
źródło komunikatu w zainstalowanej wtyczce jAsyncAPI 5.1.0: inspekcja
`AsyncAPIReference`, a nie `JsonSchemaRefReference`. Przy czterech `$ref`
zastosowano właściwy identyfikator. Wyciszenie `YAMLIncompatibleTypes` znajduje
się przed kluczem `enum`, ponieważ mechanizm YAML wyszukuje komentarze przy
kluczach, a nie elementach sekwencji. Potwierdzono to w deskryptorze wtyczki
oraz implementacji suppressora z IntelliJ IDEA 2026.3.

111 testów rdzenia i 28 runtime, Javadoc z `-Werror` oraz pakowanie przechodzą
na JDK 17.0.21 i 25.0.1. Samego silnika inspekcji IntelliJ nie uruchamiano
automatycznie. Dowody: [ide-warnings.json](evidence/stage3/ide-warnings.json).

Korekta zmiennych lokalnych: usunięto z nich niedozwolone przez inspekcję
adnotacje JSpecify, pozostawiając `var` i jawne sprawdzenia null. Runtime
sprawdza teraz nullable wynik kodeka przed rzutowaniem do `T`, więc warunek
nie zależy od nie-nullowego ograniczenia parametru typu. Końcowe sprawdzenie
tej korekty na JDK 25.0.1: 111 testów rdzenia, 28 runtime, Javadoc i pakowanie
przeszły w 45 s. Nie powtarzano pozostałych modułów ani całej macierzy.
Dowody: [ide-warnings-follow-up.json](evidence/stage3/ide-warnings-follow-up.json).

## Pilotaż do wznowienia

Na prośbę użytkownika utworzono worktree
`C:/Develop/Projekty/ngo-patients/worktrees/promptjig-runtime/tichodroma-svc`,
gałąź `feature/promptjig-runtime-pilot`, punkt wyjścia `444440d1`.
Przeniesiono do niego kopię istniejącej konfiguracji opt-in, kontraktu/locka,
mappera i testów pilotażowych. Nie dodano integracji runtime i nie uruchamiano
kompilacji/testów usługi. Główny checkout usługi zachowano bez nowych zmian.

Po naprawie kompilacji trzeba uzgodnić aktualny punkt wyjścia worktree, a następnie:

1. Podłączyć lokalny runtime wyłącznie do jawnego pilotażu.
2. Wprowadzić nową wersję kontraktu i osobne system/user `.st`, z enumami z kontraktu.
3. Włączyć wygenerowane `VERSION_REF`, schemat odpowiedzi w pakiecie wejściowym
   audytu oraz zapis naruszeń po zatwierdzeniu surowej odpowiedzi.
4. Zastąpić czytanie pól przez runtime i zachować jawny mapper domenowy.
5. Sprawdzić fallback, trwałość audytu, propagację kontekstu i separację zwykłego
   buildu/CI od pilotażu. Domyślna aktywacja wymaga osobnej zmiany po akceptacji.
