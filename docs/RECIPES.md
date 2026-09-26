# Recettes / Recipes

Toutes les recettes se font sur une table de fabrication 3 × 3. Elles apparaissent dans
le livre après l’obtention de cuivre ou de redstone.

Comme HomeLink Farm, les appareils utilisent les composants électroniques partagés de
HomeCore 1.7.0 :

- **Carte électronique HomeLink** / HomeLink Circuit Board (`homecore:homelink_circuit_board`) :
  4 cuivre + 4 redstone + 1 quartz → 2 cartes ;
- **Microprocesseur HomeLink** / HomeLink Microprocessor (`homecore:homelink_microprocessor`) :
  1 carte + 4 pépites d’or + 2 cuivre + 1 redstone + 1 quartz → 1 microprocesseur ;
- **Module de communication HomeLink** / HomeLink Communication Module
  (`homecore:homelink_communication_module`) : 1 carte + 1 microprocesseur + 2 cuivre +
  2 redstone + 1 quartz + 1 éclat d’améthyste → 1 module ;
- **Module de contrôle HomeLink** / HomeLink Control Module
  (`homecore:homelink_control_module`) : 1 carte + 1 microprocesseur + 1 comparateur +
  2 cuivre + 2 redstone + 1 lingot de fer → 1 module.

Ces composants s’assemblent uniquement à l’**Electronics Workbench** de HomeCore.
Chaque appareil prend le composant qui correspond à son rôle :

- le Controller, qui gère le réseau, demande un microprocesseur ;
- le Link et le Repeater, qui relient les inventaires et relaient le réseau, demandent
  un module de communication ;
- le Deposit, qui range les objets tout seul, demande un module de contrôle ;
- le Terminal demande une carte, le coffre de débordement une carte et un microprocesseur ;
- la clé USB reste un simple outil vanilla.

## Storage Controller — 1

```text
I C I
R M R
I C I
```

- I : lingot de fer / iron ingot × 4
- C : lingot de cuivre / copper ingot × 2
- R : poudre de redstone / redstone dust × 2
- M : microprocesseur HomeLink / HomeLink microprocessor × 1

## Storage Terminal — 1

```text
I G I
R B R
I C I
```

- I : lingot de fer / iron ingot × 4
- G : verre / glass × 1
- R : poudre de redstone / redstone dust × 2
- B : carte électronique HomeLink / HomeLink circuit board × 1
- C : lingot de cuivre / copper ingot × 1

## Storage Link — 4

```text
  C
R B R
  I
```

- C : lingot de cuivre / copper ingot × 1
- R : poudre de redstone / redstone dust × 2
- B : module de communication HomeLink / HomeLink communication module × 1
- I : lingot de fer / iron ingot × 1

## Storage Repeater — 1

```text
  B
R Q R
I I I
```

- B : module de communication HomeLink / HomeLink communication module × 1
- R : poudre de redstone / redstone dust × 2
- Q : répéteur de redstone / redstone repeater × 1
- I : lingot de fer / iron ingot × 3

## Storage Deposit — 1

```text
  C
R H R
  B
```

- C : coffre / chest × 1
- R : poudre de redstone / redstone dust × 2
- H : entonnoir / hopper × 1
- B : module de contrôle HomeLink / HomeLink control module × 1

## Coffre de débordement / Storage Overflow Chest — 1

```text
  B
C M C
I I I
```

- B : carte électronique HomeLink / HomeLink circuit board × 1
- C : coffre / chest × 2 (54 emplacements, comme un double coffre)
- M : microprocesseur HomeLink / HomeLink microprocessor × 1
- I : lingot de fer / iron ingot × 3

## Clé USB de liaison / USB Linking Key — 1

```text
  I
R C R
```

- I : lingot de fer / iron ingot × 1
- R : poudre de redstone / redstone dust × 2
- C : lingot de cuivre / copper ingot × 1

Une pioche de n’importe quel niveau récupère les six blocs. Chacun possède un modèle
3D distinct. Les géométries et matériaux vanilla utilisés sont documentés dans
`assets/homelink_storage/MATERIALS.md`.

Avec JEI, le bouton **+** d’une de ces recettes, Terminal ouvert, récupère les
composants et les matériaux qui manquent depuis le réseau de stockage.
