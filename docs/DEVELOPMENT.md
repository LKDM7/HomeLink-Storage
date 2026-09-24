# Développement

Le serveur détient les identités, le graphe de couverture, les inventaires découverts,
l’index, les permissions et les statistiques. HomeCore est une dépendance obligatoire,
compilée en composite Gradle depuis le checkout configurable `homecore_dir`. Aucun
package interne HomeCore ou HomeLink Dashboard n’est importé.

## Organisation

- `block`, `blockentity`, `registry` : blocs, identités et cycle de vie.
- `storage/network` : nœuds de couverture, connexions découvertes et associations.
- `storage/inventory` : ItemHandler et identité canonique des doubles coffres.
- `storage/index` : compteurs incrémentaux par item et data components.
- `menu`, `network` : sessions validées, snapshots fragmentés et deltas.
- `client` : thème Dashboard, manuel, recherche, écran et marqueur privé.
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
sans créer de dépendance binaire. `StorageManualView` fournit cinq chapitres traduits.
Les quatre modèles JSON ont des géométries propres et utilisent des textures vanilla,
ce qui laisse les packs de ressources contrôler les matériaux.

## Vérifier

Utiliser Java 21 et le wrapper : `build`, `runSmoke`, puis `runPersistence` avec
`-PpersistencePass=write` et `-PpersistencePass=read`. `releaseBundle` rassemble
les deux JAR dans `build/release`. Les scénarios exécutables emploient le vrai runtime
Minecraft depuis le source set `verification` et ne sont pas embarqués.

## Extensions possibles

Les identités et positions préparent les labels et cartes. Le comparateur utilise la
capacité ; un futur mode item pourra interroger l’index. Dépôt, routage et widgets
spécialisés restent hors de la V1. Un Dashboard générique peut déjà consommer
l’appareil via HomeCore.
