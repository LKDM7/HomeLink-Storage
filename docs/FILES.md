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

Tous les chemins abrégés après le premier sont relatifs à
`src/main/java/fr/lkdm/homelink/storage`.

## Ressources

- metadata : `src/main/templates/META-INF/neoforge.mods.toml`
- langues : `assets/homelink_storage/lang/en_us.json`, `fr_fr.json`
- modèles/blocstates : Controller, Terminal, Link et Repeater
- matériaux : `assets/homelink_storage/MATERIALS.md`
- recettes et déblocages : Controller, Terminal, Link et Repeater
- loot tables : les quatre blocs
- tag de minage : `data/minecraft/tags/block/mineable/pickaxe.json`

Les chemins de ressources abrégés sont relatifs à `src/main/resources`.

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

## Documentation

- `README.md`
- `docs/USER_GUIDE.md`
- `docs/RECIPES.md`
- `docs/DEVELOPMENT.md`
- `docs/HOMECORE.md`
- `docs/VALIDATION.md`
- `LICENSE`
