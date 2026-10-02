# Fichiers principaux du projet

## Code

- `src/main/java/fr/lkdm/homelink/storage/HomeLinkStorage.java`
- `block/StorageBlock.java`
- `blockentity/StorageBlockEntity.java`
- `config/StorageConfig.java`
- `registry/StorageRegistries.java`
- `storage/network/CoverageNode.java`
- `storage/network/InventoryConnection.java`
- `storage/network/StorageBinding.java`
- `storage/inventory/StorageInventoryAdapter.java`
- `storage/inventory/StorageWithdrawal.java`
- `storage/index/StorageIndex.java`
- `homelink/StorageDevice.java`
- `menu/StorageMenu.java`
- `network/StorageData.java`, `StoragePackets.java`, `StorageRequestBudget.java`
- `client/StorageClient.java`
- `client/rendering/LocateRenderer.java`, `StorageTheme.java`
- `client/screen/StorageScreen.java`
- `client/widget/StorageButton.java`, `StorageManualView.java`
- `logistics/pipe/StoragePipeBlock.java`, `StoragePipeBlockEntity.java`
- `logistics/network/PipeNetworkManager.java`
- `logistics/transit/TransitLedger.java`, `TransitPacket.java`
- `logistics/filter`, `logistics/sync`
- `client/logistics/PipeScreen.java`, `ItemCatalog.java`, `PipeRenderer.java`

Tous les chemins abrégés après le premier sont relatifs à
`src/main/java/fr/lkdm/homelink/storage`.

## Ressources

- metadata : `src/main/templates/META-INF/neoforge.mods.toml`
- langues : `assets/homelink_storage/lang/en_us.json`, `fr_fr.json`
- modèles/blockstates : Controller, Terminal, Link, Repeater, Deposit, Overflow et Pipe
- matériaux : `assets/homelink_storage/MATERIALS.md`
- recettes et déblocages : les sept blocs et la clé USB
- loot tables : les sept blocs
- textures des pipes : `assets/homelink_storage/textures/block/pipe_{glass,graphite,copper}.png`
- enfants métal/verre et raccords : `assets/homelink_storage/models/block/storage_pipe*.json`
- tag de minage : `data/minecraft/tags/block/mineable/pickaxe.json`

Les chemins de ressources abrégés sont relatifs à `src/main/resources`.
`scripts/generate_pipe_models.py` régénère les assets des pipes ;
`scripts/validate_pipe_models.py` contrôle leurs références et les 4096 états.

## Vérification

Le source set `src/verification` contient les scénarios Minecraft : couverture,
inventaires, index, Terminal, manuel, gestion, localisation, HomeCore, robustesse,
recettes, paquets, persistance et orchestration smoke. La structure de monde vide
se trouve dans ses ressources.

`ControllerChecks`, `DoubleChestChecks` et `WithdrawalChecks` valident le montage mural,
le voyant et les retraits. `src/dashboardVerification` contient le scénario optionnel
qui pilote le vrai écran HomeLink Dashboard avec les trois mods chargés ensemble.

`TerminalMountChecks` vérifie le Terminal mural et sur pied ; son modèle mural est
`assets/homelink_storage/models/block/storage_terminal_wall.json`.

`PipeClientChecks` couvre les vrais payloads, le catalogue, les échelles GUI,
les cargaisons visibles, les deux couches de rendu et les montages sur six faces.
Les tests logiques sont dans `src/test` ; les GameTests et reprises du ledger
restent dans `src/verification`. Captures sélectionnées : `docs/images/`.

## Documentation

- `README.md`
- `docs/USER_GUIDE.md`
- `docs/RECIPES.md`
- `docs/DEVELOPMENT.md`
- `docs/HOMECORE.md`
- `docs/VALIDATION.md`
- `docs/STORAGE_PIPES.md`
- `docs/STORAGE_PIPES_ARCHITECTURE.md`
- `docs/PIPES_VALIDATION.md`
- `CHANGELOG.md`
- `LICENSE`
