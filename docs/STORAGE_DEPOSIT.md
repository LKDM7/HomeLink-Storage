# Storage Deposit

Je mets mes objets dans Storage Deposit, et HomeLink les range dans les coffres
connectés qui contiennent déjà ces objets.

## Utilisation

1. Fabriquer et poser le Storage Deposit.
2. Avec la **clé USB de liaison / USB Linking Key** existante, cliquer sur le
   Storage Controller, puis sur le Deposit. Aucun nouveau connecteur n'est nécessaire.
3. Ouvrir le Deposit et y déplacer les objets normalement ou par shift-click.

Son inventaire possède 27 emplacements. Les hoppers et tuyaux y accèdent par le
**dessus** et par les **ports** de l'arrière et des deux côtés, via la capacité NeoForge
standard ; l'avant (écran) et le dessous restent fermés. Le comparateur mesure le remplissage
comme un coffre : 0 vide, jusqu'à 15 plein. Casser le bloc libère son contenu.

Une tentative traite au maximum un emplacement non vide toutes les 20 ticks côté
serveur. Les emplacements sont parcourus à tour de rôle : un diamant sans destination
ne bloque pas les lingots placés après lui. Les coffres pleins sont ignorés dans un
ordre stable de positions. Après un transfert partiel, le reste attend son prochain
tour. Une seule destination recevant des objets est utilisée par tentative.

Sans coffre contenant la même variante, l'objet attend **5 secondes** dans le Deposit,
puis part dans un **Coffre de débordement** du réseau (voir plus bas). Ce délai laisse
à un coffre tout juste « amorcé » à la main le temps d'être indexé. Une fois dans le
coffre de débordement, les exemplaires suivants y vont aussi, puisqu'il contient
désormais cette variante. Un coffre ordinaire qui contient la même variante reste
toujours prioritaire sur le débordement.

Sans coffre de débordement, ou s'il est plein, l'objet reste dans le Deposit. Le
Terminal le signale alors par un badge « ⚠ N en attente », et l'entrée **En attente
(Deposit)** de son filtre de zones liste ces objets. Les raccourcis habituels (double-clic,
Maj+clic, clic droit, Ctrl+clic, clic molette, champ Quantité) les reprennent directement
depuis le Deposit.

## Coffre de débordement

Bloc de 54 emplacements, fabriqué avec une carte électronique et un microprocesseur
HomeCore. Il se pose dans un chunk couvert par un Storage Link, comme un coffre ordinaire,
et aucune liaison n'est nécessaire. Il n'a pas d'écran : un clic droit indique son
remplissage, et son contenu se reprend depuis le Terminal. Les entonnoirs et le
comparateur fonctionnent. Casser le bloc libère son contenu. Un réseau peut en avoir
plusieurs, utilisés dans un ordre stable de positions.

```text
          Carte électronique
Coffre    Microprocesseur    Coffre
Fer       Fer                Fer
```
Les composants font partie de l'identité : noms, enchantements et variantes restent
distincts selon les règles de l'index existant.

L'interface indique non connecté, Controller hors ligne, connecté/en attente,
tri en cours ou bloqué. Le nom personnalisé du Controller est affiché à l'ouverture.
Un son discret accompagne les transferts. Il n'y a aucun filtre ni règle configurable.

## Recette

```text
       Coffre
Redstone Hopper Redstone
       Lingot de cuivre
```

Résultat : un Storage Deposit. Le caisson possède un bac supérieur encastré, une
façade cyan, des bordures en cuivre et des aérations. Les textures de matériaux
vanilla reprennent la palette graphite/cuivre du Controller et du Terminal.
Le GUI utilise leur cadre métallique et leurs composants StorageTheme.

## Connexion, sauvegarde et permissions

Le Deposit hérite de la liaison UUID/position, du propriétaire et des permissions
existantes de StorageBlockEntity. La liaison requiert CONFIGURE et le menu CONTROL.
Les hoppers conservent leur comportement d'automatisation physique vanilla.
Le Deposit ne devient pas un appareil Dashboard séparé. HomeCore 1.3.0 est toujours
compilé depuis le vrai projet voisin ; aucune classe ni API HomeCore n'est copiée.

L'inventaire, la liaison et le prochain emplacement sont sauvegardés. Le délai
repart à 20 ticks au chargement ; les états affichés sont recalculés. Fermer le menu
ne suspend pas le tri. Les inventaires des Deposits sont exclus de la découverte
du Controller, évitant les boucles entre Deposits.

Un Controller déchargé suspend le tri, qui reprend quand le même UUID redevient
disponible. Un nouveau Controller placé après destruction possède un nouvel UUID :
il faut relier le Deposit avec la clé. Aucune tentative ne charge les chunks distants.

## Transferts et limites

Le Controller fournit les positions candidates depuis son index, avec l'identité
ItemVariant existante. Le tri vérifie ensuite les capacités vivantes et la présence
réelle de la variante. Il simule l'insertion, revalide la destination et la source,
insère, puis retire uniquement la quantité réellement acceptée. Chaque insertion
de slot est comptabilisée immédiatement. Seul l'inventaire candidat est réindexé.
Le Deposit ne construit aucun index et ne découvre aucun coffre.

Ces garanties supposent une capacité IItemHandler respectant le contrat NeoForge.
Cette API ne fournit pas de transaction persistante entre deux inventaires : un
handler tiers qui modifie son contenu puis lève une exception, falsifie son reliquat
ou modifie abusivement la source pendant l'insertion n'offre pas de rollback fiable.
Un arrêt brutal du processus pendant la sauvegarde n'offre pas non plus une
transaction disque inter-chunks. Les arrêts/rechargements normaux sont testés.

Les métriques Dashboard supplémentaires restent hors de cette V1 : le Controller
continue d'exposer ses métriques habituelles sans registre parallèle de Deposits.

## Vérification

```powershell
.\gradlew.bat build test
.\gradlew.bat runPersistence -PpersistencePass=write
.\gradlew.bat runPersistence -PpersistencePass=read
.\gradlew.bat runSmoke -PsmokeLanguage=fr_fr
```

`test` n'a pas de sources JUnit dans ce projet. Les assertions de comportement
s'exécutent dans les vrais environnements GameTest et client Minecraft. Les classes
de vérification sont exclues du JAR livré. Les logs et captures restent sous `build/`.

Les tests utilisent un serveur dédié et des joueurs simulés pour les autorisations,
ainsi qu'un client réel avec serveur intégré pour les paquets du menu. Cela ne
remplace pas une session manuelle avec plusieurs clients distants ni une validation
de toutes les capacités des mods tiers.

### Résultats exécutés le 24 septembre 2026

- Build final : `build test runSmoke -PsmokeLanguage=en_us`, succès.
- Client français : succès ; client anglais : succès. Modèles, traductions, ouverture,
  synchronisation, vrai paquet de shift-click et fermeture après destruction vérifiés.
- Sauvegarde `write` puis `read` dans deux JVM distinctes : succès, objets et UUID
  de liaison du Deposit conservés.
- Suite serveur finale : **7 GameTests requis réussis**, comprenant les assertions
  détaillées ci-dessous. Les tests du Controller, Links, index, retraits, HomeCore,
  recettes et paquets existants passent aussi dans les deux lancements client.
- Contenu du JAR contrôlé : Deposit présent, aucune classe HomeCore ou de vérification embarquée.

| Cas demandé | Vérification exécutée |
| --- | --- |
| 1–2 : non connecté / connecté | États, clé existante et liaison serveur |
| 3–4 : destination / sans destination | Comptage avant/après et amorçage manuel d'une destination |
| 5–7 : plein, plusieurs destinations, partiel | 64 objets proposés, 23 acceptés, 41 conservés ; coffre plein ignoré |
| 8 : plusieurs stacks | Maximum 64 objets d'un seul emplacement par tentative, rotation sans famine |
| 9–10 : Controller détruit / disponible | Arrêt, conservation, reprise après restauration du même UUID |
| 11 : destination détruite | Index périmé et suppression pendant la simulation |
| 12 : chunks déchargés | Vrai déchargement/rechargement de destination et Controller ; aucun chargement par Deposit |
| 13 : hopper | Transfert réel du hopper vanilla vers la capacité du Deposit ; avant et dessous fermés, autres faces ouvertes, dans les 4 orientations |
| 14 : shift-click | Appel serveur et vrai paquet émis par le client |
| 15 : redémarrage | Sauvegarde puis lecture par un autre processus Minecraft |
| 16 : plusieurs Deposits | Comptage conservé vers un même Controller |
| 17–18 : moddé / composants | Item du mod, nom personnalisé distinct puis identique |
| 19 : multijoueur | Serveur dédié, deux identités simulées, VIEWER refusé ; pas de session à deux clients distants |
| 20 : destruction | Quantité exacte au sol, loot du bloc et remplacement avec inventaire vide |

Autres assertions : fermeture GUI sans perte, comparateur 0/intermédiaire/15,
index dont le dernier exemplaire a disparu, Link détruit, acceptation réelle de 7
après simulation de 32 (25 conservés), refus tardif, modification de la source
pendant simulation et tentative récursive rejetée.

Journaux conservés sous `build/validation/reports/` : `deposit-write.log`,
`deposit-read-final.log`, `deposit-client-fr.log`, `deposit-client-en.log`.
Captures : `deposit-fr.png`, `deposit-en.png` dans le même dossier.

## Fichiers concernés par cette évolution

Sous `src/main/java/fr/lkdm/homelink/storage/` :

- Ajouts : `block/DepositBlock.java`, `blockentity/DepositBlockEntity.java`,
  `menu/DepositMenu.java`, `client/screen/DepositScreen.java`,
  `storage/inventory/StorageInsertion.java`.
- Extensions : `block/StorageBlock.java`, `blockentity/StorageBlockEntity.java`,
  `storage/index/StorageIndex.java`, `registry/StorageRegistries.java`,
  `HomeLinkStorage.java`, `client/StorageClient.java`.

Ressources ajoutées : blockstate, modèle de bloc, modèle d'item, loot table,
recette et advancement `storage_deposit`. Ressources étendues : langues EN/FR,
tag `mineable/pickaxe`.

Vérification : `DepositGameTests.java`, `DepositCapabilityChecks.java`, `DepositClientChecks.java`,
`StorageSmoke.java`, `PersistenceGameTests.java`, `RecipeChecks.java`,
`StorageValidation.java`. `LinkKeyChecks.java` corrige deux appels incompatibles
avec Minecraft 1.21.1 déjà présents avant cette évolution.

Autres fichiers : `build.gradle` exige le marqueur de succès du test client Deposit ;
`README.md` référence ce guide.
