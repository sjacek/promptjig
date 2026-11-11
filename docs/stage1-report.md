# Promptjig — etap 1: rdzeń i plugin Gradle

Data: 2026-11-11. **Status: implementacja lokalna i akceptacja wykonane;
publikacja Plugin Portal oraz domyślna aktywacja w usłudze pozostają otwarte.**
Nie oznacza to pełnego zamknięcia etapu publikacyjnego.

Raport zachowuje stan etapu 1. Późniejsze generowanie kodu i zmiana pilotażu
usługi są opisane w [raporcie etapu 2](stage2-report.md).

## Dostarczone zachowanie

Wersja kandydująca 0.1.0 ma niezależny od narzędzia budowania rdzeń i plugin
`io.github.sjacek.promptjig`. `promptContractsValidate` jest podłączone do `check`;
`promptContractsLock` jest wywoływane wyłącznie jawnie. Walidator zbiera
diagnostyki z plikami, wierszami, kolumnami, regułami i wskaźnikami JSON.
Raporty JSON/tekst mają stabilną kolejność i ścieżki względne.

Zaimplementowano reguły analizy: `contract-schema`, `template-exists`,
`template-parameters`, `schema-subset`, `unique-id`, `lock`, `lock-baseline`.
Weryfikacja obejmuje semantykę typów/enumów, wymaganych pól i lokalnych referencji,
poprawność wzorców, brak cykli samych aliasów i łączne użycie parametrów ST4.
Konkretny schemat rekurencyjny jest dozwolony. Pliki błędne strukturalnie są
raportowane bez wykonywania niewiarygodnych dalszych sprawdzeń semantycznych.

Zamrożono format v1 i ramkowanie `promptjig-fingerprint-v1` opisane w
[specyfikacji](contract-format.md). Historia blokady pozostaje bez zmian dla
istniejących wersji. Aktualizacja dopisuje nowe wpisy po walidacji, serializuje
współpracujących piszących przez blokadę pliku i zastępuje wynik atomowo, gdy
system plików to obsługuje. Błąd nie powoduje częściowego zapisu.

Plugin zawiera prywatny pakiet runtime (core, Jackson 2, ST4 i zależności) i
rozpakowuje go do ignorowanego stanu zadania. Walidacja działa w Worker API z
izolacją classloadera; biblioteki nie są wystawiane jako zależności buildscriptu
konsumenta. JAR-y zawierają tekst MIT. Publikacja marker/POM/module metadata
została sprawdzona w lokalnym repozytorium Maven.

Przewodnik: [Gradle](gradle-plugin.md). Wydanie: [procedura](releasing.md).

## Akceptacja

Poniższa macierz opisuje pierwotną akceptację etapu 1. Rozszerzenie testów i
dokumentacji API opisano osobno w sekcji „Uzupełnienie komentarzy i testów”.

- 39 testów rdzenia, w tym agregacja i pozycje diagnostyk, semantyka schematów,
  początkowa blokada, odmowa nadpisania, wersjonowanie, baseline, brak częściowych
  zapisów, równoległe dopisywanie, dokładna wielkość liter nazw szablonów,
  przenośne sortowanie plików i niezależnie generowane raporty po relokacji.
- 9 scenariuszy TestKit na każdej z 8 kombinacji poniżej: 72 wykonania testów
  konsumenckich. Test tożsamości potwierdza faktyczną wersję Gradle i JVM procesu.
- Sprawdzone: configuration cache, cache po relokacji, jawne `lock check`,
  niewykonywanie lock przez samo `check`, baseline, izolacja od Jacksona 3 hosta
  i instalacja opublikowanego lokalnie markera bez classpath TestKit.
- `check assemble :core:spike` i `:gradle-plugin:publishPlugins --validate-only`
  przeszły; walidacja publikacji nie wysyła artefaktów do Plugin Portal.
- Zachowano referencyjny odcisk kontraktu z etapu 0 jako wektor regresyjny v1.

| Gradle | JDK 17 | JDK 21 | JDK 25 |
| --- | --- | --- | --- |
| 9.0.0 | 9/9 | 9/9 | poza macierzą wsparcia Gradle |
| 9.1.0 | 9/9 | 9/9 | 9/9 |
| 9.9.1 | 9/9 | 9/9 | 9/9 |

Wykonania lokalne: Windows x64, build authoring Gradle 9.9.1 / JDK 25;
konsument JDK 17/21/25. JDK 17.0.21 pobrano z oficjalnego Adoptium do
ignorowanego cache projektu i zweryfikowano SHA-256 archiwum:
`98518ecf554465057ee5509462631deeee374bc368c12a411d225cd29381f84b`.
Podsumowania każdej kombinacji: `docs/evidence/stage1/gradle-*-jdk*.json`.
Workflow Linux odwzorowuje tę macierz, ale nie został uruchomiony w GitHubie
w ramach lokalnej implementacji.

Dokumentacja: 31 testów narzędzi wiki i `mkdocs build --strict` przeszły.
`check_docs.py` zgłosił wyłącznie braki front matter w dwóch plikach z innych,
równoległych zmian workspace: `ebok/fixtures/ui21-fix-20261111/README.md` i
`ebok/fixtures/uxaa-15-rc1-upgrade-20261111/README.md`. Tych plików nie zmieniano.
Lokalne linki siedmiu plików dokumentacji Promptjig, katalog wersji i składnia
workflow YAML zostały sprawdzone osobno.

## Uzupełnienie komentarzy i testów

Uzupełniono Javadoc publicznego API rdzenia, właściwości DSL, zadań i parametrów
workera: domyślne ścieżki, własność danych, wyjątki, zasady cache oraz jawne
aktualizowanie historii. Komentarze przy algorytmach wyjaśniają zakres blokady
piszących, dopuszczalną rekurencję schematów, zachowanie niezmienionych bajtów
locka i izolację runtime. `check` sprawdza teraz Javadoc obu modułów z
`-Xdoclint:all` i `-Werror`, bez wyłączania ostrzeżeń o brakujących opisach.

Zestaw rdzenia rozszerzono z 39 do **51 testów**. Dodano przypadki zagnieżdżonych
zakresów i pierwszych lokalizacji parametrów ST4, odrzucania zewnętrznych wywołań,
błędnego UTF-8, granic ścieżek, zachowania historii po usunięciu kontraktu,
numerycznego porządku wersji, zewnętrznego baseline oraz deterministycznych
raportów i bezpiecznych pojedynczych linii logów. Rozszerzono też przypadki
niepoprawnej struktury locka. Test raportów ujawnił i zabezpieczył brak
rozstrzygania kolejności diagnostyk o tej samej lokalizacji i treści: porównanie
uwzględnia teraz również JSON Pointer oraz severity.

TestKit rozszerzono z 9 do **11 scenariuszy** o nadpisanie lokalizacji przez DSL
oraz zmianę i usunięcie zewnętrznego baseline przy ponownym użyciu configuration
cache. Wszystkie 11 scenariuszy przeszło na **Gradle 9.0.0 / JDK 17** oraz
**Gradle 9.9.1 / JDK 25** (22 wykonania). Pozostałych sześciu kombinacji z
pierwotnej macierzy nie powtarzano w tym uzupełnieniu. Pełne `check assemble`
przeszło na authoring Gradle 9.9.1 / JDK 25; nie wykonywano inferencji ani
publikacji. Wyniki i aktualne hashe artefaktów:
[comments-and-tests.json](evidence/stage1/comments-and-tests.json).

## Pilotaż tichodroma-svc

P5 jest spełnione dla pilotażu: zmiany E1 są w historii `origin/develop` i
bazowego HEAD usługi `444440d1c0366843b9e1efb885178d85a29f5dc8`.
Dodano kontrakt wszystkich 15 pól obok istniejącego `.st` i blokadę w katalogu
modułu. Jawne `-PpromptjigBuildDir=<checkout>` podłącza lokalny plugin przez
dependency substitution i jego walidator do `check`.

`promptContractsLock promptContractsValidate` w usłudze przeszły offline.
`check --dry-run` potwierdził `promptContractsValidate` w grafie pilotażu.
Na odizolowanej kopii kontraktu, oryginalnego promptu i blokady walidacja
przechodziła przed zmianą. Dopisanie nowego LF do kopii `.st` bez zwiększenia
wersji dało błąd `lock` z poleceniem zwiększenia wersji. Hash oryginalnego
promptu i bajty blokady pozostały bez zmian. Dowód:
`docs/evidence/stage1/service-changed-template.json`.

Nie zmieniono ekstraktora, adnotacji audytu, modelu, opcji transportu ani bazy
danych. Nie wykonywano testów aplikacji, inferencji ani wdrożenia. Uzupełniono
AI-03–AI-05 w obu standardach z jawnym etapowaniem generowania i integracji runtime.

## Otwarte kroki i granice

Repozytorium Promptjig nie miało pierwszego commita podczas prac. Właściciel
musi zatwierdzić źródła, utworzyć tag i opublikować przez przygotowany workflow;
Codex nie wykonał `git commit` ani `git push`. Plugin Portal może wymagać
zatwierdzenia pierwszego wydania. Lokalna walidacja publikacji nie oznacza
publikacji ani akceptacji portalu.

Próba odczytu strony identyfikatora w Plugin Portal zwróciła HTTP 400 dla HEAD
i GET; dostępności/własności nazwy nie uznano za potwierdzoną. Przed pierwszym
uploadem trzeba ją ponownie sprawdzić w portalu (P1).

Do tego czasu pilotaż usługi jest opt-in. Po dostępności 0.1.0 trzeba zastąpić
preview normalną deklaracją pluginu oraz dołączyć niezależną blokadę bazową w CI
usługi. CI Promptjig ma już taki mechanizm dla własnego przykładu.

Generowanie kodu/DSL `generate` (etap 2), runtime Spring AI i naprawa odpowiedzi
(etap 3), adapter Maven (etap 4) i ewaluacje modeli pozostają poza tym zakresem.
Publikacja core do Maven Central także nie została wykonana; przygotowano lokalny
artefakt i metadane. Plugin Portal nie zależy od tej publikacji core.
