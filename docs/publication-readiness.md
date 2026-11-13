# Gotowość buildu i publikacji 0.1.0

Przygotowanie wydania bibliotek 0.2.0 opisuje aktualna
[procedura Maven Central](maven-central.md). Poniższy raport zachowuje wyniki
historycznych kontroli 0.1.0; nie potwierdza publikacji bibliotek 0.2.0.

Aktualizacja 2026-11-12: **plugin 0.1.0 został zaakceptowany i jest publicznie
dostępny w [Gradle Plugin Portal](https://plugins.gradle.org/plugin/io.github.sjacek.promptjig/0.1.0).**
Strona wersji zwraca HTTP 200. Zdalna publikacja osobnych bibliotek core/runtime
pozostaje oddzielnym zadaniem. Konsument `examples/gradle` przeszedł `check`
z pluginem pobranym z Portalu, bez `promptjigPluginBuild` ani included build:

```powershell
.\gradlew.bat -p examples/gradle check --refresh-dependencies --configuration-cache --configuration-cache-problems=fail --console=plain
```

Wynik: `BUILD SUCCESSFUL`, 1 min 56 s. Poniższe wyniki zachowują historyczny
stan kontroli przed publikacją z 2026-11-11.

Sprawdzenie 2026-11-11. **Build lokalny i uwierzytelniona walidacja Plugin Portal
przechodzą. Wydanie przez GitHub wymaga jeszcze konfiguracji i zatwierdzonego
tagu.** Nie wykonano uploadu, commita, taga ani push.

## Zweryfikowane lokalnie

JDK 25.0.1, Gradle 9.9.1:

```powershell
.\gradlew.bat clean check assemble :core:spike :gradle-plugin:publishPlugins --validate-only :core:publishAllPublicationsToStagingRepository :spring-ai:publishAllPublicationsToStagingRepository --console=plain
```

Wynik: `BUILD SUCCESSFUL`, 4 min 30 s. 111 testów rdzenia, 28 runtime i
19 scenariuszy TestKit bez błędów. Część zadań kompilacji/testów/Javadoc została
odtworzona z poprawnego build cache; czyszczenie usunęło poprzednie artefakty.
Offline spike, kontrola pluginu, pakowanie, Javadoc i lokalny staging przeszły.
`publishPlugins --validate-only` wykonano z lokalnie skonfigurowanymi kluczami;
nie wysyła artefaktów i nie oznacza zakończenia przeglądu pierwszej publikacji.

Oba przykłady przeszły osobne `clean check` z jawnym included build oraz
`--configuration-cache --configuration-cache-problems=fail`:

- `examples/generation`: generacja, kompilacja modeli i jeden test aplikacyjny,
  33 s, configuration cache zapisany.
- `examples/gradle`: walidacja kontraktów, 15 s, configuration cache zapisany.

JAR-y core, pluginu i runtime zawierają `META-INF/LICENSE`. Marker pluginu
wskazuje `io.github.sjacek.promptjig:promptjig-gradle-plugin:0.1.0`; jego POM
nie wymaga zewnętrznej publikacji core, a JAR zawiera prywatny runtime ZIP.
Przetwarzanie YAML obu workflow oraz kolejność i przekazywanie referencji
sekretów do walidacji/uploadu sprawdzono lokalnie parserem Jackson YAML.
Dowody: [publication-0.1.0.json](evidence/publication-0.1.0.json).

## Znaleziony błąd CI i poprawka

Ostatni [workflow Compatibility](https://github.com/sjacek/promptjig/actions/runs/43092407604)
dotyczy commita `3906fea67812b1fc517aec62705d662e44d3b9c3`. Osiem zadań
macierzy Gradle/JDK przeszło. `core-and-packaging` zatrzymał się na
`publishPlugins --validate-only`: `Missing publishing keys`.

Ta komenda wymaga kluczy także w trybie walidacji. Usunięto ją ze zwykłego
workflow CI. W ręcznym workflow wydania walidacja jest osobnym krokiem z
`GRADLE_PUBLISH_KEY` i `GRADLE_PUBLISH_SECRET`, przed uploadem. Wcześniej te
zmienne otrzymywał dopiero krok uploadu, więc również wydanie mogło zatrzymać
się na walidacji. Zwykły build, testy, Javadoc i przykłady pozostają w CI.

Poprawka jest lokalna. Nie uruchamiano ponownie starego workflow ani nie
publikowano nowych źródeł, więc GitHub nie potwierdził jeszcze tej poprawki
i zmian runtime. Wymagany jest commit/push właściciela i zielony workflow
na tej rewizji.

## Uzupełnienie komentarzy i testów workflow

Dodano testy parsujące rzeczywiste workflow z zasobów śledzonych przez Gradle
oraz wykonujące ich skrypt sprawdzania taga w Bash. Kontrolowane atrapy Git i
Gradle dopuszczają wyłącznie odczyt/porównanie; nie wymagają kluczy ani sieci
i nie zmieniają repozytoriów. Testy chronią brak zależności zwykłego CI od
Portalu, osiem par macierzy, ręczne uruchomienie publikacji, zakres sekretów
oraz zatrzymanie procesu po błędzie guarda, buildu lub walidacji.

Skrypt odrzuca także pustą wersję i przerywa natychmiast po błędzie rozpoznania
taga/HEAD. Wyniki poleceń Git są przypisywane osobno, przed porównaniem.
Komentarze wyjaśniają ograniczenie kluczy do kroków Portalu, sprawdzanie indeksu
i plików śledzonych oraz odpowiedzialność właściciela za wybór commita z zieloną
macierzą Compatibility. Workflow wydania sam nie odpytuje statusu wcześniejszego CI.

Dedykowany source set i zadanie `:gradle-plugin:workflowTest` zawierają
**20 przypadków**. Parser YAML pozostaje poza classpath TestKit; istniejący test
izolacji Jacksona zachowano. Workflow są kopiowane przez
`processWorkflowTestResources`, więc ich zmiana unieważnia cache testów.

Końcowe sprawdzenie na JDK 25.0.1 / Gradle 9.9.1:
`:gradle-plugin:clean check assemble --configuration-cache-problems=fail`
zakończone sukcesem w 26 s: 111 testów rdzenia, 28 runtime, 19 TestKit
i 20 workflow, razem **178**. Nowe testy workflow wykonano; część istniejących
wyników, w tym TestKit, przywrócono z poprawnego build cache. Nie ponawiano
całej macierzy ani walidacji online Portalu. Dowody:
[publication-workflow-tests.json](evidence/publication-workflow-tests.json).

## Pozostałe warunki wydania

Odczyt metadanych GitHub z potwierdzonymi uprawnieniami administratora wykazał:

- brak sekretów Actions w repozytorium;
- brak skonfigurowanych środowisk, w tym `plugin-portal`;
- brak tagów wydania lokalnie i na GitHubie;
- bieżące zmiany runtime i poprawka CI pozostają niezacommitowane.

Właściciel powinien skonfigurować środowisko `plugin-portal` i sekrety
`GRADLE_PUBLISH_KEY` / `GRADLE_PUBLISH_SECRET` (ewentualnie sekrety repozytorium),
zatwierdzić i wypchnąć zmiany, uzyskać zielone CI, utworzyć i wypchnąć tag
`v0.1.0`, a następnie uruchomić ręczny workflow wydania dla tego taga. Odczytano
wyłącznie obecność/nazwy ustawień; nie kopiowano ani nie ujawniano wartości kluczy.

Pierwszą publikację ocenia zespół Gradle; poprawny dry run nie gwarantuje jej
akceptacji. Zasady: [publikowanie w Plugin Portal](https://plugins.gradle.org/docs/publish-plugin).
Odczyt publicznej strony ID zwrócił HTTP 400, więc nie potwierdzono w ten sposób
publicznej dostępności pluginu ani nie wnioskowano z tego o zajętości ID.

Portal publikuje plugin Gradle. `promptjig-core` i `promptjig-spring-ai` mają
lokalne publikacje Maven, ale zdalne wydanie tych bibliotek wymaga osobnej
konfiguracji Maven Central. Pilotaż `tichodroma-svc` pozostaje wstrzymany.
