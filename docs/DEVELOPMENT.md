# Développement

Le serveur détient les identités, le graphe de couverture, les inventaires découverts,
l’index, les permissions et les statistiques. HomeCore est une dépendance obligatoire,
résolue depuis une publication Maven versionnée, ou compilée depuis le checkout `homecore_dir` avec `-PuseLocalDependencies=true`. Aucun
package interne HomeCore ou HomeLink Dashboard n’est importé.

## Organisation

- `block`, `blockentity`, `registry` : blocs, identités et cycle de vie.
- `storage/network` : nœuds de couverture, connexions découvertes et associations.
- `storage/inventory` : ItemHandler et identité canonique des doubles coffres.
- `storage/index` : compteurs incrémentaux par item et data components.
- `logistics` : graphe physique des pipes, ports par face, routage borné,
  filtres et ledger persistant des cargaisons.
- `menu`, `network` : sessions validées, snapshots fragmentés et deltas.
- `client` : thème Dashboard, manuel, recherche, écran et marqueur privé.
- `client/logistics` : configuration des raccords, catalogue d'items et rendu du transit.
- `homelink` : appareil, métriques, actions et événements publics HomeCore.
- `config` : cadencement et limites serveur.

## Couverture par chunk

Les CoverageNode persistants représentent les Links et Repeaters. Un Link est une
racine active lorsqu’il est chargé. Un Repeater devient actif si son chunk partage
un côté cardinal avec un chunk actif ; une propagation itérative couvre les chaînes,
sans accepter les diagonales ni activer une boucle dépourvue de Link.

Le code demande uniquement des chunks déjà chargés via `getChunkNow`. Il inspecte
leurs BlockEntities dans une limite globale de candidats, puis résout la capability
`IItemHandler` sur les faces standard. L’identité d’inventaire est déterministe à
partir du Controller et de la position canonique. Les doubles coffres sont regroupés,
y compris à la frontière de deux chunks chargés. Un même inventaire découvert par
plusieurs sources reste une seule connexion.

La topologie et les contenus sont vérifiés périodiquement et par lots. Une découverte
tronquée ne permet jamais de conclure que les inventaires non visités ont disparu.
Les handlers sont acquis brièvement ; l’index conserve un exemplaire par variante et
des compteurs, pas une copie permanente de chaque pile.

## Réseau et sécurité

L’ouverture du Terminal envoie un snapshot puis des deltas limités en lignes et
octets. Les commandes sont contrôlées par session, distance, UUID, état chargé,
capability et permissions HomeCore. LOCATE revalide la position canonique côté
serveur. Le retrait exige CONTROL, une ligne réellement envoyée dans la session et
un UUID d’inventaire enregistré. StorageWithdrawal résout un handler chargé, vérifie
les composants, simule la place disponible puis livre uniquement le résultat de
l’extraction réelle. Le contenu source est réindexé immédiatement. Le serveur sérialise
les requêtes : une quantité affichée devenue obsolète ne peut créer des objets.

Les associations, noms et zones sont sauvegardés en NBT ; l’index est reconstruit.
HomeCore persiste les HomeNetworks. Le retrait d’un Controller désenregistre son
appareil et retire son appartenance en conservant les réseaux partagés.

## Interface et ressources

`StorageTheme` reprend les constantes visuelles publiques du projet
HomeLink Dashboard observé au commit `6f110fb96b6a090e4c2e30de242a6dee3f14a4e4`,
sans créer de dépendance binaire. `StorageManualView` fournit six chapitres traduits,
dont les Storage Pipes. Les appareils utilisent des géométries propres et des textures
vanilla ; les pipes ont trois textures RGBA originales (verre, graphite, cuivre).
Les modèles composites NeoForge séparent métal opaque et verre translucide.
Les formes de sélection des pipes sont calculées à la demande et suivent le tube
biseauté et ses raccords. Voir [l'architecture des pipes](STORAGE_PIPES_ARCHITECTURE.md).

Les scripts d'assets utilisent uniquement Python 3, sans dépendance de build ou runtime :

```powershell
python scripts/generate_pipe_models.py
python scripts/validate_pipe_models.py
```

Si Python est fourni par uv : `uv run --offline scripts/validate_pipe_models.py`.
Le générateur régénère modèles, blockstates et textures de manière déterministe.
Le validateur contrôle les 4096 états, l'exclusivité collier/raccord, les références,
les UV, les couches de rendu et l'épaisseur des panneaux de verre ; il n'évalue pas
la qualité visuelle, vérifiée séparément dans le vrai client avec `runSmoke`.

## Vérifier

Utiliser Java 21 et le wrapper : `build`, `runSmoke`, puis `runPersistence` avec
`-PpersistencePass=write` et `-PpersistencePass=read`. `releaseBundle` rassemble
les JAR du mod et de ses dépendances obligatoires dans `build/release`. Les scénarios exécutables emploient le vrai runtime
Minecraft depuis le source set `verification` et ne sont pas embarqués.
La CI contrôle aussi les modèles avec `python3 scripts/validate_pipe_models.py`.
Les [résultats des pipes](PIPES_VALIDATION.md) distinguent les tests exécutés,
les captures et les contrôles manuels restant à faire.

## Extensions possibles

Les identités et positions préparent les labels et cartes. Le comparateur utilise la
capacité ; un futur mode item pourra interroger l’index. Le Deposit assure le tri vers les inventaires existants ; les règles de routage personnalisées restent hors de cette version. Un Dashboard générique peut déjà consommer
l’appareil via HomeCore.
