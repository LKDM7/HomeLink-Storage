# HomeLink Storage 1.0.0

Minecraft 1.21.1 · NeoForge 21.1.251 · Java 21.

HomeLink Storage connecte, indexe, recherche, localise et surveille de vrais
inventaires Minecraft. Le Controller et le Terminal permettent désormais de retirer
les objets : chaque objet reçu est réellement extrait du coffre sélectionné.

## Installation et prise en main

Le [Storage Deposit](docs/STORAGE_DEPOSIT.md) ajoute un coffre d'entrée de 27 slots :
une stack par seconde est rangée dans un inventaire connecté contenant déjà la même
variante. Il se relie au Controller avec la clé USB de liaison existante.

Installer `homelink_storage-1.0.0.jar` et `homecore-1.3.0.jar` dans le dossier
`mods` du client et du serveur NeoForge.

1. Placer un Storage Controller.
2. Placer un Storage Link dans le chunk à couvrir : il découvre les inventaires
   accessibles de tout ce chunk.
3. Pour couvrir plus loin, placer un Storage Repeater dans un chunk cardinal voisin,
   puis prolonger la chaîne de chunk en chunk.
4. Main vide et accroupi, cliquer sur le Controller, puis sur les Links, Repeaters
   et le Terminal à lui associer.
5. Ouvrir le Terminal normalement. Le bouton `?` affiche le manuel intégré.

Le Controller peut se poser sur toute face d’un bloc. Son voyant clignote lorsque
des inventaires sont connectés. Sélectionner un objet et son coffre, puis utiliser
« Prendre 1 » ou « Prendre 64 » ; la permission HomeCore CONTROL est nécessaire.

Le Terminal possède deux modèles : sur pied lorsqu’il est posé sur le dessus d’un
bloc, et panneau mince lorsqu’il est fixé à un mur ou sous un bloc.

Les nœuds ne chargent jamais un chunk de force. Un répéteur diagonal ou séparé de la
chaîne reste hors ligne. Le guide détaillé explique aussi la recherche, la localisation,
les zones et les permissions HomeCore.

- [Guide utilisateur](docs/USER_GUIDE.md)
- [Recettes](docs/RECIPES.md)
- [Architecture et développement](docs/DEVELOPMENT.md)
- [Contrat réel HomeCore](docs/HOMECORE.md)
- [Vérifications exécutées](docs/VALIDATION.md)
- [Fichiers du projet](docs/FILES.md)

## Construire

Installer un JDK 21, définir `JAVA_HOME`, puis utiliser le wrapper Gradle :

```powershell
git clone https://github.com/LKDM7/HomeCore ../HomeCore
git -C ../HomeCore checkout 0557f41f80b0a900990b11b85146e1937676a615
.\gradlew.bat build
.\gradlew.bat releaseBundle
.\gradlew.bat runClient
```

L’emplacement de HomeCore est configurable avec
`-Phomecore_dir=../autre-checkout`. Le composite Gradle compile la vraie API
HomeCore 1.3.0 sans embarquer ses classes dans Storage. HomeLink Dashboard n’est pas
une dépendance : Storage reprend son langage visuel et expose ses données au Dashboard
uniquement au travers de HomeCore.

## Vérification

```powershell
.\gradlew.bat runSmoke -PsmokeLanguage=fr_fr
.\gradlew.bat runSmoke -PwithDashboard -PsmokeLanguage=fr_fr --no-configuration-cache
.\gradlew.bat runPersistence -PpersistencePass=write
.\gradlew.bat runPersistence -PpersistencePass=read
```

`runSmoke` démarre un vrai client Minecraft et un serveur intégré, exécute les
scénarios, prend des captures puis ferme le jeu. Les tests de persistance utilisent
deux processus serveur et le même monde. Les validations sont exclues du JAR distribué.

Les quatre blocs possèdent des géométries 3D distinctes, construites avec des matériaux
vanilla pour rester compatibles avec les packs de ressources. Les inventaires moddées
passent par la capability standard NeoForge ; chaque mod tiers n’a pas été testé
individuellement. Le test optionnel Dashboard utilise le vrai checkout voisin
`../HomeLink-Dashboard` (configurable avec `-Pdashboard_dir`).

Licence Apache-2.0, auteur LKDM.
