# GUI commun HomeLink / Shared HomeLink UI

Les écrans Terminal/Controller, Deposit, manuel, choix de raccord, CONNECTION / FILTER et récupération utilisent HomeCore 1.14.0, API publique 1.9.0. `StorageTheme` et `StorageButton` sont supprimés.

| Ancien code / Previous code | Remplacement / Replacement |
| --- | --- |
| tokens `StorageTheme` | `HomeLinkTheme` |
| `frame`, `panel`, `screw` | `HomeLinkUi` |
| style des `EditBox` | `HomeLinkUi.input(edit)` |
| `StorageButton.builder(...)` | `HomeLinkButton.builder(...)` |
| onglets CONNECTION / FILTER | `HomeLinkButton.navigation(selected)` |
| surfaces adaptatives Terminal/Controller et Pipes | `HomeLinkScreenLayout.fit(...)` |
| `StorageTheme.status(...)` | mapping métier local `StorageStatusColors.color(...)` vers `HomeLinkStatusTone` |

Les slots vanilla du Deposit gardent leurs coordonnées. Terminal et Controller affichent des listes et champs, sans slots vanilla ; leurs colonnes et boutons s'adaptent à la largeur disponible. Le protocole Terminal/Controller/Deposit/Pipes conserve ses responsabilités. Les couleurs de couverture dans le monde gardent les mêmes valeurs. Les futures interfaces Pipes consomment directement le kit ; aucune nouvelle palette locale n'est nécessaire.

Les écrans Terminal/Controller préfèrent une surface de 380 × 220 pixels GUI ; au viewport 320 × 240, la largeur devient 304 avec 8 pixels de marge et la hauteur reste 220. Les colonnes, champs, boutons, listes cliquables et coordonnées exposées aux viewers de recettes utilisent cette même géométrie. Les textes trop longs sont tronqués avec ellipsis. Les manuels se recomposent à la largeur disponible. Deposit garde 300 × 224, soit 316 × 240 avec les marges ; sous cette taille, réduire l'échelle GUI pour préserver ses slots. Les écrans Pipes utilisent le layout adaptatif ; leurs captures de validation aux échelles GUI 2, 3 et 4 montrent les contrôles dans la fenêtre, y compris à 320 × 240 pixels GUI.

**English:** use `fr.lkdm.homecore.api.client.ui` exclusively on the client. Keep filtering, drafts, item catalogs, access checks, transport and inventory geometry in Storage. HomeCore owns only visual tokens and stateless rendering/control primitives. Always keep localized labels, tooltips, keyboard focus and native widget narration. HomeCore is a separate required JAR, never embedded in the Storage JAR; Dashboard is unnecessary for displaying any of these screens. Future HomeLink Furnace can use the same kit independently.

Declare the explicit Gradle dependency `fr.lkdm.homecore:homecore:1.14.0` and metadata range `[1.14.0,2.0.0)`. Adjacent-source composite builds require that compatible version and do not automatically download updates from GitHub.

**Responsive layout:** Terminal/Controller have no vanilla slots. They prefer 380 × 220, shrinking to 304 × 220 in a 320 × 240 scaled viewport. Fields, columns, controls, item hitboxes and recipe-viewer coordinates follow the fitted width; manuals reflow and labels use ellipsis. The existing smoke now checks both screens and their manuals at 640 × 360 and 320 × 240, including bounds, overlap, Tab focus and real control/row clicks; results must be reported after execution. Deposit preserves its fixed inventory slots and needs 316 × 240 with margins. Pipes fit the available viewport; scales 2/3/4 were checked visually, including a 320 × 240 scaled viewport.
