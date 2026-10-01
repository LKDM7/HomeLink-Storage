# Contrat HomeCore

## Contrat actuel

Storage 1.2.0 demande HomeCore **1.12.0**, API **1.8.0**, avec la plage `[1.12.0,2.0.0)`. La version exacte est déclarée dans `gradle.properties` ; un composite local utilise un checkout de même version et ne nécessite pas Maven local.

Le Controller implémente `NetworkMember` et les demandes de liaison passent par `DashboardAPI.bindDevice()`. HomeCore applique les permissions sur le réseau quitté et le réseau rejoint. Le Storage Deposit expose `ItemApi.BLOCK` de type `INPUT` sur ses faces d’entrée ; les transferts FarmBot et Quarry utilisent ce même contrat. La liaison interne des composants Storage au Controller reste une fonction de la clé Storage.

Depuis Storage 1.2.0, le Controller publie `StockProvider.CAPABILITY`. Un consommateur (par
exemple HomeLink Tasks) lit le stock indexé sans importer les classes de Storage. Chaque appel
revérifie le réseau lié, la permission `VIEW` du joueur et l'alimentation ; sinon la réponse est
`UNAVAILABLE`. Un inventaire déchargé, hors couverture ou dont le dernier scan est trop ancien rend
la réponse `PARTIAL`. Les quantités sont attribuées à la position canonique de chaque inventaire,
et la lecture ne rescanne, n'extrait et ne réserve rien. `READ_AND_WITHDRAW` n'est indiqué que si
le joueur a aussi la permission `CONTROL`.

## Historique des inspections

Les versions et commits ci-dessous décrivent les audits datés ; ils ne sont pas les dépendances actuelles.

Source de vérité : https://github.com/LKDM7/HomeCore

Mise à jour du 26 septembre 2026 : Storage cible **HomeCore 1.7.0**, commit
`fecc72b70cbe31d8a4b667f70ff866e400925385` de `main`. La plage déclarée est
`[1.7.0,2.0.0)`. L'API publique ne change pas. La version 1.7.0 ajoute
`homecore:homelink_communication_module` et `homecore:homelink_control_module`. Le Link
et le Repeater prennent désormais le module de communication, le Deposit le module de
contrôle (voir [RECIPES.md](RECIPES.md)).

Mise à jour du 25 septembre 2026 : Storage cible **HomeCore 1.6.1**, commit
`fa8d6854d8686195d760ca9e5988bbcf2c29c8bb` de `main` (déjà poussé sur `origin`). La plage
déclarée est `[1.6.1,2.0.0)`. L'API publique `fr.lkdm.homecore.api.*` n'a pas changé depuis
1.3.0 (`DashboardAPI.API_VERSION` vaut toujours `1.3.0`). La version 1.6.1 ajoute les
composants partagés `homecore:homelink_circuit_board` et `homecore:homelink_microprocessor`,
assemblés à l'Electronics Workbench. Les recettes Storage les utilisent désormais (voir
[RECIPES.md](RECIPES.md)), comme HomeLink Farm. Minecraft 1.21.1, Java 21, NeoForge
21.1.250, plage `[21.1.250,21.2)`. Storage utilise 21.1.251, compatible avec cette plage.

Inspection initiale du 19 septembre 2026 : commit `0557f41` (HomeCore 1.3.0).

Inspectés : build.gradle, settings.gradle, gradle.properties, metadata NeoForge,
README, docs/PROTOCOL.md, docs/VALIDATION.md, exemple et packages publics.

Toutes les intégrations passent par `fr.lkdm.homecore.api.*` :

- `DashboardAPI.devices(server).register(device)` / `unregister(UUID)` : cycle de vie serveur.
- `DashboardDevice` : `id()`, `deviceType()`, `displayName()`, `status()` ; méthodes
  optionnelles `metrics()`, `actions()`, `eventTypes()`, `position()`, `dimension()`, `schema()`.
- `DeviceMetric.builder(id, label, MetricTypes.LONG, value)` et `setValue(value)` : révisions.
- `DeviceAction.button(id, label)` ; authentification via
  `DashboardAPI.executeAction(ServerPlayer, networkId, deviceId, actionId, value)`.
- `DashboardAPI.events(server).publish(DeviceEvent)` : événements déclarés par l'appareil.
- `DashboardAPI.networks(server)` : réseaux et membres persistants.
- `DashboardAPI.hasPermission(ServerPlayer, networkId, Permission)` : contrôle d'accès.
- `DashboardAPI.registerDeviceProvider(BlockEntityType<T>, DeviceProvider<? super T>)` :
  factory publique, sans balayage automatique des chunks.

Aucun import `internal`, aucune réimplémentation des concepts HomeCore.
Le Controller enregistre un vrai `DashboardDevice`, cinq métriques typées, quatre
types d'événements et l'action `refresh_index`. Le réseau, les permissions et la
limitation du débit reposent sur les services publics HomeCore. Le cycle de vie
retire les appareils déchargés du registre et conserve les associations persistantes.

HomeCore 1.3.0 possède un statut WARNING, mais son
exécuteur d'actions exige ONLINE et connectionState considère WARNING hors ligne.
Cette sémantique est respectée : l'action distante est refusée en WARNING ; le
Terminal conserve son rafraîchissement local avec la permission CONTROL.
Les scénarios en jeu vérifient l'abonnement du client, les cinq métriques, le retour
d'action et la réception d'un événement réel. HomeCore n'a pas été modifié.
