# Validation exécutée

## Version 1.0.0 initiale — 19 septembre 2026

Les huit phases initiales ont été compilées et exécutées dans le vrai runtime Minecraft.
Le dernier cycle `build runSmoke releaseBundle -PsmokeLanguage=fr_fr` a réussi à
12:10:51 avec HomeCore 1.3.0, NeoForge 21.1.251 et Minecraft 1.21.1.

Les scénarios couvraient les trois blocs initiaux, ItemHandler, doubles coffres, index
incrémental, recherche, variantes, zones, snapshots/deltas, LOCATE, comparateur,
permissions et réseau HomeCore, métriques, actions, événements et hystérésis. Un test
de charge a compté 442 368 objets dans 6 912 slots. Le transport a fragmenté
512 milliards d’objets simulés en 42 paquets d’au plus 19 441 octets.

Deux processus distincts avaient aussi validé la sauvegarde et la reprise de l’UUID,
du propriétaire, du HomeNetwork, des associations, zones, noms et 63 objets.

## Révision GUI, manuel et couverture par chunk

La révision a été testée le 19 septembre 2026 avec le vrai client français :

- `verificationClasses` : succès après compilation des sources principales et des
  nouvelles fixtures.
- `runSmoke -PsmokeLanguage=fr_fr` : succès à 15:29:22, durée 1 min 22 s.
- `runPersistence -PpersistencePass=write` : succès à 15:30:35.
- `runPersistence -PpersistencePass=read` : succès à 15:31:24 dans un second
  processus, avec 63 objets et le même réseau retrouvés.

Le scénario de couverture pose un Link et vérifie deux barrels éloignés horizontalement
et verticalement dans son chunk. Il exclut l’inventaire du chunk voisin, évite les
doublons avec un second Link, conserve l’UUID après nouvelle découverte, active une
chaîne de Repeaters cardinaux et refuse une diagonale et une boucle sans Link. La
casse/réparation, la racine réassociée, le nœud déchargé sans chargement forcé et le
double coffre à cheval sur la frontière de chunk sont vérifiés.

Le client a aussi vérifié les quatre recettes, l’association Link/Repeater/Terminal,
le manuel `?` et sa navigation, la GUI française, les commandes de gestion, LOCATE,
les permissions, l’index, la robustesse et HomeCore serveur/client. Les marqueurs
`STORAGE_COVERAGE_CHECKS_OK`, `STORAGE_MANUAL_CHECKS_OK` et
`STORAGE_SMOKE_OK` sont présents dans le journal.

Les captures `storage-manual.png`, `storage-terminal.png` et
`storage-locate.png` ont été ouvertes et inspectées. Le manuel et le Terminal
reprennent bien le cadre graphite/cuivre du Dashboard ; la localisation et le modèle
3D du Terminal sont visibles en monde.

## Controller mural, retraits et vrai Dashboard — 19 septembre 2026

Le test combiné `runSmoke -PwithDashboard -PsmokeLanguage=fr_fr --no-configuration-cache`
a réussi à 18:14:24 avec les trois vrais mods : HomeCore 1.3.0, HomeLink Storage
1.0.0 et HomeLink Dashboard 1.0.0. Le scénario pilote le véritable DashboardScreen,
ses actions et son transport HomeCore, sans simulation d’API Dashboard.

Le build et le même scénario complet ont été rejoués avec la version finale du
handler d’extraction : `build runSmoke -PwithDashboard -PsmokeLanguage=fr_fr
--no-configuration-cache`, succès à 18:17:07 (2 min 3 s).

- Controller posé via BlockItem sur les six faces, collision en contact avec le support,
  voyant allumé/éteint et extinction après disparition de l’inventaire.
- Double coffre de frontière : 54 slots, retrait de 56 diamants des deux moitiés,
  simulation sans mutation, coffre simple après séparation, refus du partenaire
  incohérent/déchargé et absence de chargement forcé.
- Retrait : conservation entre coffre et joueur, plusieurs slots, stock épuisé,
  quantité bornée, inventaire joueur plein ou partiellement libre, composants conservés,
  refus des UUID étrangers, non-propriétaires et membres VIEWER.
- Vraie commande GUI : un diamant reçu, coffre de 31 à 30 diamants, total synchronisé
  de 161 à 160 objets.
- Dashboard : cinq métriques décodées, action refresh réussie, WARNING et événements,
  inventaire détruit/reconnecté, VIEWER refusé pour CONTROL, fermeture après coupure radio.

Les captures du Terminal et du Dashboard ont été ouvertes et inspectées. Deux problèmes
de fixtures ont été corrigés avant ce succès : Minecraft convertit normalement un
demi-coffre cassé en coffre simple ; le Home Server du Dashboard doit être posé sur
ses deux blocs et le joueur doit rester sur une plateforme à portée.

Les dépôts HomeCore et Dashboard n’ont pas été modifiés. Le retrait respecte la capability
d’extraction d’origine, y compris si la vue de lecture combine un coffre moddé.

Persistance du retrait : le passage `runPersistence -PpersistencePass=write` a retiré
un diamant de la pile de 64 puis sauvegardé 63 objets à 18:18:41. Le passage `read`
dans un second processus retrouve 63 objets, le même UUID et le même HomeNetwork
à 18:20:03. Les deux GameTests ont réussi.

## Portée des validations finales

Terminal mural : le 19 septembre 2026, `build runSmoke -PwithDashboard
-PsmokeLanguage=fr_fr --no-configuration-cache` réussit à 19:07:51. Le nouveau
`TerminalMountChecks` pose réellement le Terminal sur les six faces, vérifie le
choix panneau/pied, l’orientation, l’épaisseur de 4/16, le contact au support et
l’ouverture du menu. Le client vérifie tous les modèles orientés, puis les scénarios
de retrait et le vrai Dashboard réussissent. Les 29 JSON sont valides.

Le JAR est contrôlé par `verifyReleaseJar` : metadata, dépendance obligatoire à
HomeCore, ressources françaises/anglaises et absence de classes HomeCore ou de
validation embarquées. La tâche `test` de Storage est NO-SOURCE ; les succès décrits
ici proviennent des scénarios Minecraft réellement exécutés.

La compatibilité avec les inventaires tiers repose sur ItemHandler mais chaque mod
n’a pas été essayé. Les mesures sont des tests fonctionnels bornés, pas un benchmark
de serveur multijoueur de longue durée.

## Retrait sans choisir le coffre et intégration JEI — 25 septembre 2026

Le Terminal retire maintenant depuis n'importe quel inventaire du réseau (action serveur
`withdraw_any`, en commençant par l'inventaire sélectionné). Les raccourcis de la grille
et le champ Quantité ont été ajoutés, ainsi qu'un plugin JEI optionnel (`compat/jei`) :
R/U sur la grille, synchronisation de la recherche et bouton **+** qui récupère les
ingrédients manquants via l'action bornée `withdraw_batch` (16 variantes au plus, 2 304
objets au plus). Le protocole réseau passe en version `2`, et la valeur d'une commande
est limitée à 512 caractères.

HomeCore est compilé depuis un clone local de `0557f41` (1.3.0). Le checkout voisin
`../HomeCore` est en 1.6.1 et n'a pas été modifié.

- `build runSmoke -PsmokeLanguage=fr_fr` : succès à 19:36:20. Les 23 marqueurs requis
  sont présents, dont les nouveaux `STORAGE_WITHDRAW_ANY_CHECKS_OK`,
  `STORAGE_INGREDIENT_PLAN_CHECKS_OK` et `STORAGE_RECIPE_VIEWER_CHECKS_OK jei=false`.
- `runSmoke -PwithJei -PsmokeLanguage=fr_fr` (JEI 19.57.0.449) : succès à 19:37:35,
  plugin chargé, `STORAGE_RECIPE_VIEWER_CHECKS_OK jei=true`.
- `runSmoke -PsmokeLanguage=en_us` : succès.
- `runPersistence` en passes `write` puis `read` : 7 GameTests requis réussis à chaque passe.

Assertions ajoutées :

- Serveur : l'inventaire préféré est vidé en premier ; le retrait continue d'un coffre
  à l'autre jusqu'à épuisement ; une demande de 100 objets traverse plusieurs stacks et
  inventaires ; un inventaire joueur presque plein limite le transfert à 2 objets ; les
  bornes 0 et 2 305 sont refusées ; le VIEWER est refusé ; une ligne périmée ne duplique rien.
- Planification des ingrédients : les objets déjà portés sont comptés ; le maximum est
  borné par le stock sans composants et par la taille de stack ; un emplacement manquant
  est signalé ; les alternatives de tag et une variante explicite sont gérées ; le
  codage des lots est vérifié.
- Raccourcis de clic (sélection, stack, demi-stack, 1 objet, remplir). Vrai Ctrl+clic
  client : 1 diamant reçu, coffre de 31 à 30 diamants.
- Codec : un lot de 512 caractères fait l'aller-retour, une valeur de 513 caractères est refusée.
- JEI : la présence du pont correspond au chargement de JEI ; la recherche est
  synchronisée (`#tag` devient `$tag`) seulement quand le bouton est actif.

Non couvert automatiquement : le clic réel sur le bouton **+** dans l'interface JEI (la
planification et la commande serveur sont testées séparément), R/U sur la grille et EMI.

## HomeCore 1.6.1 et recettes à composants — 25 septembre 2026

Storage cible maintenant HomeCore 1.6.1 (`fa8d685`, plage `[1.6.1,2.0.0)`), compilé
depuis le checkout voisin `../HomeCore`. L'API publique n'a pas changé. Les recettes
des appareils utilisent les composants HomeCore, comme HomeLink Farm : microprocesseur
pour le Controller, carte électronique pour le Terminal, le Link, le Repeater et le
Deposit. La clé USB est inchangée.

- `build runSmoke -PsmokeLanguage=fr_fr` : succès, 24 marqueurs, dont
  `STORAGE_RECIPE_CHECKS_OK` avec les nouvelles grilles et les vrais items HomeCore.
- `runSmoke -PsmokeLanguage=en_us` : succès.
- `runSmoke -PwithJei -PsmokeLanguage=fr_fr` : succès, `jei=true`.
- `runSmoke -PwithDashboard -PsmokeLanguage=fr_fr --no-configuration-cache` : succès
  avec les vrais HomeCore 1.6.1 et HomeLink Dashboard, `STORAGE_DASHBOARD_CHECKS_OK`.
- `runPersistence` en passes `write` puis `read` : 7 GameTests requis réussis à chaque passe.

## Terminal seul point de consultation — 25 septembre 2026

Seul le Terminal ouvre l'interface du réseau (consultation, retrait, localisation,
gestion). Le Controller, les Links et les Repeaters n'ont plus d'écran. Un clic droit
affiche leur état dans la barre d'action : pour le Controller, les inventaires en ligne,
le nombre d'objets et le remplissage ; pour un Link ou un Repeater, s'il est relié ou
non, s'il est actif ou hors ligne, et le nombre d'inventaires qu'il couvre. Ce message
ne donne ni objet, ni nom, ni position de coffre. Le serveur refuse aussi le menu du
réseau pour toute autre source qu'un Terminal. L'écran du Deposit ne montre que ses
27 emplacements d'entrée. Le geste accroupi et la clé USB de liaison ne changent pas.

- `build runSmoke -PsmokeLanguage=fr_fr` : succès, 25 marqueurs, dont
  `STORAGE_TERMINAL_ONLY_CHECKS_OK`. Un vrai clic droit sur le Controller, un Link et
  un Repeater n'ouvre aucun menu, un menu forcé sur ces blocs est invalide, et les
  états relié, non relié et Controller sont vérifiés.
- `runSmoke -PsmokeLanguage=en_us` : succès.
- `runSmoke -PwithDashboard ... --no-configuration-cache` : succès, `STORAGE_DASHBOARD_CHECKS_OK`.
- `runPersistence` en passes `write` puis `read` : 7 GameTests requis réussis à chaque passe.

## Gestion sur le Controller, Terminal limité aux objets — 25 septembre 2026

Le Controller ouvre maintenant son propre écran de gestion : nom du réseau, noms des
inventaires, création et attribution des zones, oubli des inventaires hors ligne et
actualisation. Cet écran ne reçoit aucune ligne d'objet. Le Terminal ne garde que la
recherche, les filtres, la localisation et le retrait ; il n'a plus ni bouton Gérer ni
bouton Actualiser. Le serveur n'accepte sur chaque bloc que les actions de son rôle
(`StorageMenu.allowed`). Les Links et les Repeaters n'ont toujours pas d'écran.

- `build runSmoke -PsmokeLanguage=fr_fr` : succès. `STORAGE_TERMINAL_ONLY_CHECKS_OK` vérifie
  que le Controller ouvre le mode gestion, que le Link et le Repeater n'ouvrent aucun menu,
  et que les actions sont réparties par rôle dans les deux sens.
  `STORAGE_MANAGEMENT_CHECKS_OK` passe par le vrai écran du Controller : renommage, création
  de zone, renommage et attribution d'inventaire. `STORAGE_CONTROLLER_SCREEN_CHECKS_OK`
  confirme qu'aucune ligne d'objet n'est reçue. Le retrait réel se fait ensuite depuis le
  Terminal. Captures inspectées : `storage-controller.png`, `storage-terminal.png`.
- `runSmoke -PsmokeLanguage=en_us`, `-PwithJei` et `-PwithDashboard` : succès.
- `runPersistence` en passes `write` puis `read` : 7 GameTests requis réussis à chaque passe.

## Zone d’action des Connecteurs et Répéteurs — 25 septembre 2026

Un clic droit sur un Link ou un Repeater envoie au joueur la zone couverte : son chunk et
ceux des autres nœuds du réseau, avec leur état. Le paquet `coverage` est borné à 257
chunks et le protocole passe en version `3`. Le client dessine pendant 30 s une clôture,
un quadrillage et des rideaux translucides (`CoverageRenderer`).

- `build runSmoke -PsmokeLanguage=fr_fr` : succès. `STORAGE_COVERAGE_ZONE_CHECKS_OK` vient
  d’un vrai clic droit serveur sur le Link de la scène : paquet reçu, chunk (0, 0) actif
  et marqué comme cliqué, aucun menu ouvert. Capture `storage-coverage.png` inspectée.
- `PacketChecks` : aller-retour du codec avec 257 chunks et refus de 258.
- `RecipeChecks` : un Repeater non relié ne renvoie que son chunk, inactif ; un Link
  relié se marque lui-même comme bloc cliqué.
- `runSmoke` en `en_us`, `-PwithJei` et `-PwithDashboard` : succès. `runPersistence`
  en passes `write` puis `read` : 7 GameTests requis réussis à chaque passe.

## Actualisation immédiate du Terminal et masquage de la zone — 25 septembre 2026

Synchronisation du Terminal :

- La détection des changements passe de toutes les 20 ticks à tous les 2 ticks. Quand
  rien n'a changé, elle coûte deux comparaisons de révision.
- Une commande (retrait, renommage…) est traitée comme urgente : la réponse part dans le
  même tick, jusqu'à 8 fragments d'un coup.
- La liste des inventaires n'est renvoyée que si l'un d'eux a changé (empreinte de la
  liste), et plus à chaque changement d'objet. L'en-tête ne l'efface côté client que dans
  ce cas (`LocationsReset`).
- Les lignes d'objets modifiées passent avant la liste des inventaires. La taille des
  fragments est cumulée au lieu de réencoder le paquet à chaque ligne.

Zone d'action : un second clic droit sur le même Connecteur ou Répéteur masque la zone
côté client, sans attendre le serveur. La réponse du serveur qui suit ce clic est ignorée
pendant 2 s au plus, et le message affiché devient « Zone d'action masquée ».

- `build runSmoke -PsmokeLanguage=fr_fr` : succès. Délai entre le vrai clic de retrait et
  l'affichage du nouveau total : **1 tick**, contre 9 auparavant sur la même scène. Le test
  exige 4 ticks au plus. `STORAGE_COVERAGE_TOGGLE_CHECKS_OK` vient d'un vrai clic droit
  client sur le Link : zone masquée immédiatement, toujours masquée 20 ticks après la
  réponse du serveur, aucun menu ouvert.
- `PacketChecks` : sur le réseau de 512 inventaires, un changement d'objet ne renvoie pas
  la liste des inventaires (3 fragments au plus) et le client la conserve.
- `runSmoke` en `en_us` (1 tick), `-PwithJei` et `-PwithDashboard` : succès. `runPersistence`
  en passes `write` puis `read` : 7 GameTests requis réussis à chaque passe.

## Coffre de débordement et objets en attente — 26 septembre 2026

Nouveau bloc `storage_overflow`, 54 emplacements, sans écran. Il est découvert par les
Links comme un inventaire ordinaire (capacité NeoForge). Il se fabrique avec une carte
électronique et un microprocesseur HomeCore, et utilise une palette dédiée (purpur,
blackstone, cuivre, améthyste, bande orange). Après 5 s sans destination
(`OVERFLOW_DELAY_TICKS`), le Deposit envoie l'objet au débordement. Les objets restés
dans les Deposits sont synchronisés vers les Terminals (révision `pendingRevision`,
64 variantes au plus par en-tête) et repris par l'action `withdraw_pending`, réservée
au Terminal.

- `build runSmoke -PsmokeLanguage=fr_fr` : succès, 30 marqueurs.
  `STORAGE_OVERFLOW_CHECKS_OK` vient d'un vrai serveur :
  - le coffre est découvert ;
  - l'objet connu va dans son coffre ;
  - l'objet inconnu n'est pas pris par la passe ordinaire, puis part au débordement ;
  - les exemplaires suivants suivent ;
  - un coffre ordinaire reste prioritaire ;
  - de vrais ticks du Deposit montrent qu'il n'y a aucun débordement avant 5 s, puis le transfert ;
  - un débordement plein refuse l'objet ;
  - l'objet en attente est listé, les bornes 0 et 2 305 sont refusées, et le retrait
    donne 2 objets réels.

  `STORAGE_PENDING_CLIENT_CHECKS_OK` : dans le vrai client, le filtre « En attente »
  liste le Deposit de la scène, et un Ctrl+clic donne 1 bâton de blaze (Deposit de 6
  à 5). Captures inspectées : `storage-deposit-model.png` (nouveau modèle à côté du
  Deposit et du Terminal) et `storage-terminal.png` (badge « ⚠ 5 en attente »).
- `RecipeChecks` : 6 recettes, dont celle du coffre de débordement avec les vrais items HomeCore.
- `runSmoke` en `en_us`, `-PwithJei` et `-PwithDashboard` : succès. `runPersistence`
  en passes `write` puis `read` : 7 GameTests requis réussis à chaque passe.
