# Promptjig — etap 2: generowanie kodu i lokalny pilotaż

Data: 2026-11-11. Kandydat: **0.1.0, nadal nieopublikowany**.

## Dostarczony zakres

1. `JavaSourceGenerator` w `core` generuje rekord wejścia, rekord odpowiedzi,
   zagnieżdżone rekordy/enumy i klasę stałych kontraktu. Korzysta z tych samych
   obiektów kontraktów, których odciski sprawdzono w danym przebiegu walidacji.
2. Obsługiwane są Jackson 2/3, `LocalDate`, `OffsetDateTime`, `BigInteger`,
   `BigDecimal`, referencje lokalne i rekurencyjne obiekty. Listy są kopiowane
   defensywnie, brak/null listy daje pustą listę, a null elementu jest zachowany,
   jeżeli dopuszcza go schemat `items`. Oryginalne nazwy pól i wartości enumów
   zachowują reprezentację JSON. Generowany kod zależy tylko od adnotacji Jackson.
3. `promptContractsGenerate` działa w izolowanym workerze i obsługuje cache.
   Blok `generate {}` podłącza źródła do `main` i kompilacji niezależnie od
   kolejności aplikowania pluginów Java/Promptjig. Walidacja pozostaje możliwa
   bez generowania. Generowanie nigdy nie aktualizuje locka niejawnie.
4. Testy kompilują rzeczywiste wygenerowane źródła z `--release 17`, sprawdzają
   odczyt/zapis obu rodzin Jackson, stałe w argumentach adnotacji, lokalne
   referencje, kolizje nazw, niezmienność wyników, usuwanie nieaktualnych typów,
   ochronę cudzych plików, configuration cache i odtworzenie z cache po relokacji.
5. Ekstraktor `tichodroma-svc` konstruuje `CertificateExtractionOutput` i jawnie
   mapuje go na domenę przez `CertificateExtractionPromptMapper`. Odczyt do
   `Map<String, Object>` usunięto. Testy porównują zbiory wartości wygenerowanych
   enumów z domeną i sprawdzają mapowanie wszystkich pól.

Opis API i ograniczeń: [generowanie kodu](code-generation.md).

## Weryfikacja

Poniższa macierz opisuje pierwotną akceptację etapu 2. Rozszerzenie komentarzy
i testów opisano osobno poniżej; pierwotne dowody zachowują swoje wyniki i hashe.

- **66 testów rdzenia**, bez błędów i pominięć; rzeczywista kompilacja Java 17
  i odczyt wygenerowanych rekordów przez Jackson 2 oraz 3.
- **16 scenariuszy TestKit na 8 kombinacjach Gradle/JDK** (128 wykonań).
- Przykład `examples/generation`: kompilacja źródeł, użycie stałej wersji w
  adnotacji oraz test odczytu syntetycznego JSON przez Jackson 3.
- **36 testów pilotażu usługi**: ekstraktor, mapper, import i rzeczywiste proxy
  aspektów audytu/circuit breaker. Modele są atrapami; nie ma inferencji.
- Kompilacja źródeł produkcyjnych i testowych usługi oraz Checkstyle czterech
  zmienionych plików Java przeszły. Nie uruchamiano całego zestawu aplikacji ani
  integracji z bazą; testy niezmienionych modułów zależnych pominięto w tym
  ukierunkowanym przebiegu. Usługa nadal zgłasza ogólne ostrzeżenia o deprecacjach.
- Pełne `check assemble`, Javadoc z `-Xdoclint:all -Werror`, spike offline oraz
  walidacja metadanych publikacji przeszły w końcowej kontroli kandydata.

| Gradle | JDK 17 | JDK 21 | JDK 25 |
| --- | --- | --- | --- |
| 9.0.0 | 16/16 | 16/16 | poza macierzą wsparcia Gradle |
| 9.1.0 | 16/16 | 16/16 | 16/16 |
| 9.9.1 | 16/16 | 16/16 | 16/16 |

Wyniki: `docs/evidence/stage2/gradle-*-jdk*.json`,
`service-pilot.json`, `service-generation.json` i `summary.json`.
Wykonania lokalne: Windows x64; workflow Linux przygotowano również dla nowego
przykładu, ale nie uruchamiano go zdalnie podczas tych prac.

## Uzupełnienie komentarzy i testów

Dodano Javadoc do generowanych rekordów, komponentów, enumów, fabryk wartości
i stałych kontraktu. Opisy wyjaśniają wymagalność/null według schematu, kopiowanie
list, wartości JSON oraz oddzielenie konstrukcji obiektu od walidacji. Uzupełniono
też komentarze zakresów `$defs`, zapisu plików, konfiguracji Gradle i mappera usługi.

Zestaw rdzenia wzrósł z 66 do **80 testów**: osobne `$defs` wejścia i wyjścia
przez obie rodziny Jackson, granica 254/255 komponentów rekordu, limit bajtów
modified UTF-8 stałych, znaki specjalne w nazwach/wartościach JSON, defensywne
kopiowanie list dopuszczających null elementu, konfiguracja zasobów/baseline,
niepoprawne manifesty własności oraz pełne Javadoc wygenerowanego publicznego API.

TestKit rozszerzono z 16 do **19 scenariuszy**. Dodano własny katalog wyjściowy
i prefiks zasobów, zmianę baseline podczas używania configuration cache oraz
jawne generowanie bez pluginu Java. Pełne rozszerzone zestawy przeszły na
Gradle 9.0.0 / JDK 17 oraz Gradle 9.9.1 / JDK 25 (38 wykonań). Pozostałych sześciu
kombinacji pierwotnej macierzy nie powtarzano w tym uzupełnieniu.

Dodatkowe sprawdzenie Javadoc 17 ujawniło wymaganie osobnych tagów `@param`
dla konstruktora zwartego rekordu, mimo opisanych komponentów rekordu. Generator
tworzy teraz oba zestawy opisów. Po poprawce ponowiono ukierunkowany scenariusz
konsumencki z `-Xdoclint:all -Werror` na obu wybranych parach Gradle/JDK (dwa
dodatkowe wykonania), cały zestaw 80 testów rdzenia i `check assemble`.
Bezpośrednia kontrola Javadoc 17 wygenerowanego modelu usługi również przeszła.

Pilotaż usługi ma **38 przechodzących testów**. Nowe przypadki mappera obejmują
wszystkie kody, ich kolejność i duplikaty oraz brak normalizacji lub domyślania
danych należących do czytnika/operatora. Checkstyle czterech zmienionych plików
Java przeszedł. Zestawu całej aplikacji nie uruchamiano. Szczegóły i aktualne
hashe artefaktów: [comments-and-tests.json](evidence/stage2/comments-and-tests.json).

## Granice pilotażu i publikacja

Build usługi na tej gałęzi wymaga teraz jawnego
`-PpromptjigBuildDir=<lokalny checkout>`: ekstraktor korzysta z generowanych typów,
a plugin nie jest jeszcze opublikowany. Nie dodano domyślnego założenia o
położeniu sąsiedniego repozytorium ani wygenerowanych źródeł do Git.

Kontrakt, lock, treść `.st`, opcje modelu, adnotacja audytu i baza pozostały bez
zmian. Do czasu etapu 3 ekstraktor zachowuje dotychczasowe tolerancyjne czytanie
pól przy konstruowaniu wygenerowanego rekordu: błędne daty/enumy dają null,
niepoprawne elementy list kodów są pomijane. Odpowiedź inna niż obiekt JSON
daje kontrolowany błąd `INVALID_JSON`. Pełna walidacja schematem, naprawa,
renderowanie przez rejestr kontraktów i `VERSION_REF` w audycie należą do etapu 3.

Commit, push i publikacja pozostają po stronie właściciela; zgodnie z decyzją
o odłożeniu push nie wykonano żadnej z tych operacji. Nie wykonywano wdrożenia.
