# Storage Pipes / Tuyaux de stockage

Minecraft 1.21.1, NeoForge 21.1.251, HomeLink Storage 1.4.0.
Inclus dans le JAR Storage : aucun Router, module ni mod Logistics supplémentaire.

## Français

### Montage

```text
Coffre source ======================== Coffre destination
```

Fabriquez **8 tuyaux** avec 6 lingots de cuivre, 2 blocs de verre et 1 redstone :

```text
C G C
C R C
C G C
```

Posez une conduite continue entre les contenants : **aucun Controller ni HE requis**.
Les raccords se forment automatiquement sur les faces exposant un port d'automatisation.
Les deux raccords doivent appartenir au même joueur et être validés par lui.
Vous pouvez ajouter un Controller alimenté pour sa supervision et sa récupération.
Lorsqu'il est présent, le Controller
coordonne mais ne relie pas deux branches qui se toucheraient seulement à travers lui.
Deux Controllers sur la même composante bloquent les départs, même sur un HomeNetwork commun.

Cliquez à main vide sur un raccord. Au centre d'un tuyau touchant plusieurs
contenants, choisissez d'abord la face dans le sélecteur. Chaque face a sa propre
configuration. **Rien n'est extrait avant une validation autorisée.**

### Connexion

Les sens sont toujours vus du contenant :

| Choix | Déplacement réel |
| --- | --- |
| SORTIE : COFFRE → TUYAU | Le coffre ou la machine fournit les objets. |
| ENTRÉE : TUYAU → COFFRE | Le coffre ou la machine reçoit les objets. |

Le bouton bascule entre les deux sens. Une machine à sens unique désactive le
choix interdit. Pour un inventaire générique, les restrictions sont testées par
objet et emplacement. L'écran montre le Controller, le statut et les refus.
Un autre joueur ayant enregistré la même face provoque un conflit de révision :
le brouillon reste visible, à relire avant de l'appliquer à nouveau.

### Filtre

L'onglet Filtre propose tous les objets enregistrés, même non possédés. Recherchez
par nom localisé, identifiant ou `@namespace`. Cliquez sur plusieurs icônes : un
contour et une coche indiquent la sélection. « Sélectionnés seuls » permet aussi
de retrouver les identifiants d'un mod retiré. Les icônes ne sont pas des slots.

- WHITELIST vide : aucun objet ne passe ; sinon seuls les types choisis passent.
- BLACKLIST vide : tous les objets passent ; sinon les types choisis sont exclus.
- Changer de mode conserve la sélection. Les filtres source **et** destination s'appliquent.
- Appliquer enregistre le brouillon. Annuler le rétablit ; Fermer ne l'enregistre pas.
- Le filtre porte sur le type d'objet. Le transport conserve les composants exacts
  (nom, dégâts, enchantements, contenu d'une shulker).

### Trajet, énergie, récupération

Par défaut, un départ toutes les 20 ticks transporte jusqu'à 16 objets. Sans
Controller, le coût est nul et les budgets sont partagés par propriétaire dans la
dimension. Avec Controller, le coût reste 1 HE et ses circuits partagent ses budgets. Chaque segment
prend 8 ticks : 16 segments prennent 6,4 secondes à 20 TPS, hors attentes. Un modèle
représente une cargaison entière. La destination reçoit seulement à l'arrivée.

Si une destination se remplit, le reste attend ou suit une autre route depuis sa
position actuelle. Une longue attente peut déclencher un retour physique vers la
source. Chunks déchargés, coupure HE et pause suspendent le trajet ; aucun ticket
de chunk n'est créé. Une conduite cassée garde ses cargaisons dans le ledger du
Controller, sans les placer dans l'item tuyau. En mode autonome, casser le segment
qui porte une cargaison la libère une seule fois sur place. Un coffre plein garde
la cargaison en attente dans le tube ; casser ce tube permet de la récupérer.
Un chunk déchargé conserve la cargaison dans la sauvegarde, sans drop à distance.
Après retrait d'un Controller, revalidez les raccords pour autoriser le contexte autonome.

Dans l'écran du Controller, **Pipes** ouvre la supervision et la récupération.
CONTROL permet la pause et la récupération explicite des cargaisons bloquées.
Les cargaisons actives ne sont pas récupérables par cette action. La casse du
Controller libère une fois ses cargaisons connues, même dans des chunks déchargés.
Une opération tierce de résultat incertain reste isolée pour diagnostic : elle
n'est pas automatiquement relivrée, récupérée ni libérée en drop.

### Storage existant et compatibilité

Les pipes ne remplacent pas les Links ni le Terminal. Connecter un coffre par pipe
ne l'indexe pas ; les cargaisons ne sont pas du stock disponible pour Tasks.
Le HomeNetwork ne crée aucune route physique. Un Deposit accepte les objets sur
le haut, l'arrière et les côtés ; sa façade et son dessous sont fermés. Son port
INPUT n'est jamais contourné pour extraire. Après livraison au Deposit, son tri
existant prend le relais, sans prétendre utiliser des conduites absentes.

Les machines Farm/Quarry sont intégrées via les contrats publics HomeCore, sans
import de leurs classes. La capacité réelle et les droits HomeCore déterminent
l'accès. Un double coffre a une identité canonique. L'identité partagée de wrappers
virtuels tiers n'est pas universellement détectable. Les handlers génériques ne
fournissent pas d'ACL universelle : aucune garantie globale pour les mods de claims.

V1 : pas de waterlogging, pas de déplacement par piston, pas de fluides ou d'énergie
dans les tuyaux, pas de transport interdimensionnel. Les textures utilisent les
matériaux vanilla verre teinté gris clair, deepslate poli et cuivre : aucun pack
ni shader requis. Les contrôles serveur sont documentés dans
[l'architecture](STORAGE_PIPES_ARCHITECTURE.md) ; voir aussi [la validation](PIPES_VALIDATION.md).

## English

### Setup

Craft 8 Storage Pipes with the recipe above: six copper ingots, two glass and one
redstone. Physically connect containers: **no Controller and no HE are required**.
Both endpoints must belong to and be configured by the same player. An optional
powered Controller provides its supervision/recovery and retains its existing HE costs.
The Controller coordinates; it cannot bridge separate
branches. Multiple Controllers on one component prevent dispatch.

Right-click a connector with an empty hand. Clicking the centre offers a face
selector if several containers touch it. Configure each face independently:

- **EXTRACT: CONTAINER → PIPE** makes the container a source.
- **INSERT: PIPE → CONTAINER** makes it a destination.

No extraction starts before an authorized Apply. HomeCore input/output ports
restrict the choices; generic handlers are checked per item and slot.

### Filters

The Filter tab lists all registered items, without requiring ownership or JEI/REI.
Search localized names, IDs or `@namespace`, and select multiple icons. Empty
WHITELIST blocks everything; empty BLACKLIST permits everything. Switching modes
preserves selections. Missing mod IDs remain identifiable. Apply saves the local
draft, Cancel restores saved settings. Concurrent edits report a revision conflict.
Filters match item IDs; transported stacks retain their exact components.

### Travel and recovery

Default: one departure every 20 ticks, at most 16 items, and 8 ticks per segment.
Standalone budgets are shared per owner/dimension at zero HE cost. Managed circuits
share their Controller's budget and cost 1 HE per departure. At 20 TPS, 16 segments take 6.4 seconds excluding
waiting. Cargo really leaves the source before travel and arrives afterward.
Breaking a standalone cargo's current pipe releases its items once at that segment.
Unloaded cargo remains saved. After removing a Controller, reapply endpoint settings
to authorize the standalone context. Full destinations retain the remainder in transit. Rerouting begins at the current
segment. Power loss, unloaded chunks and pause stop movement without offline catch-up.

Open **Pipes** on the Controller to inspect circuits, pause them or explicitly
recover stranded cargo with CONTROL permission. Healthy active cargo cannot be
retrieved. Breaking the Controller resolves its ledger once, including cargo in
unloaded chunks. Uncertain third-party mutations are isolated for diagnosis, not
automatically compensated.

Links, indexing and physical pipes remain separate. Cargo is not available stock.
The Deposit accepts only its top/back/sides and never permits extraction through
its INPUT port. Its existing sorting starts after pipe delivery. Farm/Quarry need
no mandatory dependency; integration uses their published HomeCore ports.

Limits: no waterlogging or piston movement; no fluids, energy transport or
interdimensional routes. Generic handlers have no universal claim ACL; shared
virtual inventories require identity support to detect aliases reliably. Clean
restart persistence is tested separately from abrupt disk-crash atomicity.

See [architecture and conservation](STORAGE_PIPES_ARCHITECTURE.md) and
[actual validation results](PIPES_VALIDATION.md).
