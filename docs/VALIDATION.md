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
