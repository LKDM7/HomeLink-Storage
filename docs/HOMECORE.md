# Contrat HomeCore inspecté

Source de vérité : https://github.com/LKDM7/HomeCore

Inspection du 19 septembre 2026 : branche distante `main`, commit
`0557f41f80b0a900990b11b85146e1937676a615`, identique au checkout local propre.
Tag et release publiés : `v1.0.0`. La branche principale fournit **1.3.0**,
Minecraft 1.21.1, Java 21, NeoForge 21.1.250, plage `[21.1.250,21.2)`.
Storage utilise 21.1.251, compatible avec cette plage.

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
