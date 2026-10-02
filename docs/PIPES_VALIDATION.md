# Storage Pipes — validation locale / local validation

Date : 2026-10-02. Windows 11, Microsoft OpenJDK 21.0.11, Minecraft 1.21.1,
NeoForge 21.1.251, HomeCore 1.13.0, HomeLink Energy 0.5.0.
Version initiale Storage 1.3.0 ; livraison locale 1.4.0.

JAR : `build/libs/homelink_storage-1.4.0.jar` (433780 octets).
SHA-256 : `bdba8c6692a4afe63e4926c652655a359fcc6201975cc3907bcef858bd270ad6`.
`verifyReleaseJar` réussit ; aucune classe de fixture ni copie de HomeCore dans
l'archive. La CI a été mise à jour localement ; aucune exécution distante ni
publication n'a été déclenchée.

## État initial et méthode

Mise à jour de la demande : les pipes fonctionnent aussi sans Controller, à coût HE
nul. La suite comporte désormais 36 GameTests : six scénarios supplémentaires
couvrent trajet autonome différé, filtres/validation explicite, propriétaire,
conservation des composants, casse avec drop unique et persistance autonome.
Les passages write et read réussissent (`build/reports/pipes/standalone-write.log`
et `standalone-read.log`), avec reprise et livraison effective de 16 objets nommés.
La fixture de reprise attend les notifications de chargement des BlockEntities.
Le Controller réenregistre aussi son Device si HomeCore l'a retiré pendant une
transition de chunk ; sa validation ne renouvelle pas les tickets vanilla.
Les résultats
FR/EN et intermods ci-dessous documentent aussi la validation initiale du mode géré.
Le client FR autonome réussit également : libellé « Mode autonome », payloads,
filtres, échelles GUI 2/3/4 et rendu des cargaisons sans Controller. Captures
inspectées dans `build/reports/pipes/standalone-fr/` ; journal
`build/reports/pipes/standalone-client-fr.log`. Le bouton Appliquer est réactivé
lorsqu'un ancien raccord géré doit être revalidé après retrait de son Controller.

Le dépôt contenait déjà une implémentation partielle non commitée des pipes.
Elle a été complétée, sans second mod et sans effacement des modifications locales.
Le build de référence et les neuf tests unitaires existants réussissaient.
Aucun échec préexistant n'a été établi par ce contrôle ; il ne constituait pas
une validation visuelle initiale ni toute la matrice intermods.

Les fixtures sont dans `src/verification` et ne sont pas incluses dans le JAR
distribué. Les commandes utilisent le wrapper et les dépendances locales résolues.
Sous PowerShell, définir `$env:JAVA_HOME='C:\Users\LKDM-\.jdks\ms-21.0.11'`.

```powershell
.\gradlew.bat build --offline
.\gradlew.bat build runPersistence --offline -PpersistencePass=write
.\gradlew.bat build runPersistence --offline -PpersistencePass=read -PpipeInterop
.\gradlew.bat build runSmoke --offline -PsmokeLanguage=fr_fr
.\gradlew.bat build runSmoke --offline -PsmokeLanguage=en_us
```

## Contrôles exécutés

- 19 tests unitaires réussis : filtres, limites/IDs/doublons, directions des ports,
  composants exacts, simulation différente de l'extraction réelle, restes partiels,
  réservations internes, chemins/cycles, fenêtres de slots, identités des raccords.
- 30 GameTests réussis aux derniers passages write et read/intermods : trajet différé, absence de destination, filtres aux deux bouts,
  faces indépendantes, budgets partagés, conflits de Controllers, absence de pont
  via le Controller, casse/reconstruction, récupération et drops uniques.
- Double coffre atteint par ses deux moitiés : identité canonique commune et refus
  d'extraction lorsqu'il est aussi configuré comme destination du circuit.
- Coupure/reprise HE et facturation d'un seul départ, distinctes des fixtures
  d'injection d'énergie. Révision concurrente, propriétaire/distance et changement
  de contexte HomeNetwork vérifiés côté serveur.
- Déchargement réel d'un chunk contenant un circuit en pause, puis rechargement :
  une seule cargaison de 16 items et aucune réextraction.
- Deux processus distincts write/read : 32 items nommés, répartis entre transit
  actif et récupération, restaurés avec configuration de face et pause conservées.
- Deposit : six faces dans quatre orientations, INPUT respecté sans extraction.
- Vrais JAR optionnels Farm 1.5.0 et Quarry 1.4.0 : 16 items de chaque sortie vers
  un Deposit. Sans `-PpipeInterop`, aucun de ces mods n'est requis.
- Les validations Storage existantes restent actives (index, retrait, Deposit,
  énergie, HomeCore, catalogue/manuel et intégration client).

Les journaux conservés sont dans `build/reports/pipes/`. Rapports unitaires :
`build/reports/tests/test/index.html` et `build/test-results/test/`.
Les assertions exactes et fixtures sont les références pour la portée de chaque test.

## Charge mesurée

Run intermods à 21:12 : 1 000 pipes, 100 raccords, quatre Controllers, 256 paquets
contenant 4 096 items (64 paquets maximum par Controller). Reconstruction : deux
pas de budget, 17,013 ms cumulées. Sur 80 appels du gestionnaire : moyenne
1,588 ms, maximum 6,109 ms. La construction des fixtures est exclue.

Les appels sont exécutés dans un serveur GameTest local avec ticks accélérés.
Le temps par segment est temporairement porté à 200 ticks pour saturer les files,
et les départs sont demandés par la fixture. Ce n'est ni une mesure de TPS d'un
serveur public, ni un benchmark de FPS, ni une mesure du volume réseau multijoueur.

## Client et captures

Clients réels FR et EN réussis, sans shader, NVIDIA RTX 5070 Ti : interface Connexion/Filtre, catalogue enregistré,
sélection multiple, application via payload serveur, touche d'inventaire saisie
dans la recherche, verre transparent et objets issus du vrai ledger en mouvement.
Les captures sont sous `build/validation/client/screenshots/` et archivées dans
`build/reports/pipes/fr/` et `build/reports/pipes/en/`, avec les journaux
`client-fr.log` et `client-en.log`. Les échelles effectives 2, 3 et 4 ont été
assertées dans une fenêtre 1280×960. La première vue utilise une petite fenêtre
854×480. Le filtre à échelle 4 et le transfert FR ont aussi été inspectés en image.

## Corrections découvertes pendant les validations

- Ancienne assertion HomeCore à cinq métriques adaptée au schéma stable à seize.
- Fixture client corrigée pour ouvrir la bonne face et initialiser ses connexions.
- Compteur de sélection raccourci pour éviter le chevauchement des contrôles.
- Modèles d'items réduits pour tenir dans le tube.
- Comptage des drops à leur apparition, même dans un chunk de fixture non actif.
- Lectures répétées du moteur corrigées : les accès vanilla renouvelaient des
  tickets UNKNOWN et empêchaient le déchargement réel de la fixture.

## Couverture manuelle restant à compléter

La matrice exhaustive demandée n'est pas entièrement automatisée. Restent notamment :

- inspection des six vues, angles/T/croisements, plafond et verticales ; outils,
  boucliers/shulkers enchantés, transparence devant eau/verre et faible éclairage ;
- deux vrais clients simultanés, révocation en direct, entrée en zone en plein
  trajet, trafic réseau mesuré et performances de rendu ;
- permutations de déchargement source/intermédiaire/destination/Controller dans
  plusieurs chunks et ordres de reprise ; fusion ambiguë derrière une frontière ;
- modpack de plusieurs milliers d'items, noms extrêmes et navigation clavier
  exhaustive ; intégration Tasks/Dashboard réels et systèmes tiers de claims ;
- crash brutal du processus/disque : les sauvegardes vanilla et le ledger ne sont
  pas une transaction disque atomique universelle.

Les handlers non conformes sont isolés lorsqu'une mutation devient incertaine.
Les aliases d'inventaires virtuels tiers et ACL de claims sans API commune restent
des limites de compatibilité, détaillées dans le guide et l'architecture.

## English scope note

These results cover the executed local tests only. The clean two-process restart,
real chunk unload/reload and optional Farm/Quarry port transfers are distinct from
abrupt crash guarantees. The remaining manual matrix above is not claimed as passed.
