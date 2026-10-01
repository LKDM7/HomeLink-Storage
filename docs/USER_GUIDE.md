# Guide utilisateur — HomeLink Storage 1.3.0

Installer HomeCore 1.13.0, HomeLink Energy 0.5.0 et HomeLink Storage 1.3.0 sur Minecraft 1.21.1 avec
NeoForge 21.1.251 et Java 21, côté client et serveur. Les quatre blocs sont
craftables et disponibles dans l’onglet créatif HomeLink Storage.

## Construire un réseau

1. Placer un **Storage Controller**. Le joueur qui le pose en devient le propriétaire.
   Il se pose sur toute face d’un bloc ; son voyant clignote lorsqu’un inventaire est connecté.
2. Placer un **Storage Link** dans un chunk contenant des coffres, barrels, shulkers
   posées ou inventaires compatibles. Un seul Link couvre tout son chunk chargé.
3. Main vide, s’accroupir et cliquer sur le Controller pour le sélectionner.
4. Toujours accroupi, cliquer sur le Link et le **Storage Terminal** pour les associer.
5. Ouvrir le Terminal avec un clic droit normal.

Le Terminal choisit son modèle selon la face cliquée : sur le dessus d’un bloc,
il garde son pied ; sur une paroi, il devient un écran mural de quatre pixels
d’épaisseur, orienté vers vous. Il peut aussi se fixer sous un bloc. Utiliser
Maj pendant la pose sur un coffre pour éviter d’ouvrir ce dernier.

Le Link cherche les inventaires accessibles dans toutes les BlockEntities de son chunk,
sans balayer le monde entier. Un double coffre est un seul inventaire logique, y compris
lorsqu’il traverse une frontière de chunk, dès lors que ses deux moitiés sont chargées.
Plusieurs Links couvrant le même inventaire ne doublent ni l’inventaire ni ses objets.

## Étendre avec des répéteurs

Un **Storage Repeater** ajoute son propre chunk à la couverture du Controller. Il doit
être relié à un Link actif ou à un autre Repeater actif par un côté cardinal :
nord, sud, est ou ouest. La chaîne peut ensuite continuer de chunk en chunk.

```text
[chunk Link] — [Repeater] — [Repeater] — [Repeater]
```

Les diagonales ne relient pas les nœuds et une boucle de Repeaters sans Link reste
hors ligne. Chaque Repeater se lie au Controller avec le même geste accroupi que le
Link. Le réseau ne force jamais le chargement des chunks : un nœud déchargé conserve
son identité, mais ses données sont signalées comme indisponibles ou anciennes jusqu’au
retour du chunk.

## Terminal et manuel intégré

Le Terminal reprend le style de HomeLink Dashboard : cadre graphite, accents cuivre,
états colorés et boutons biseautés. Il présente les totaux, la capacité, les zones,
les objets et leurs emplacements.

Cliquer sur le bouton **?** ouvre le manuel en jeu. Ses cinq chapitres expliquent la
mise en route, la couverture Link/Repeater, la recherche, la surveillance et le
dépannage. La molette fait défiler le texte ; les flèches, les touches gauche/droite
ou Page précédente/Page suivante changent de chapitre.

## Rechercher et localiser

La recherche utilise le cache client reçu du serveur. Elle accepte le nom traduit,
l’identifiant comme `minecraft:diamond`, le namespace et les tags préfixés par `#`.
Le délai de saisie évite une requête réseau à chaque caractère. Le tri par nom ou
quantité, le filtre de zone et la vue par variante sont disponibles. Les data
components distinguent notamment les enchantements, potions et objets configurés.

Sélectionner un objet affiche sa quantité dans chaque inventaire, sa zone, sa position
et sa distance. Sélectionner ensuite un emplacement et cliquer sur **Localiser** :
le serveur revérifie le droit, l’inventaire et son chunk avant d’envoyer un marqueur
privé de dix secondes. Un inventaire absent ou déchargé ne peut pas être localisé.

## Retirer des objets

Ouvrir le Terminal. Il n’est plus nécessaire de choisir le coffre :
le serveur puise dans tous les inventaires en ligne qui contiennent l’objet, en
commençant par l’inventaire sélectionné dans la liste de droite.

| Geste sur la grille | Effet |
| --- | --- |
| Clic gauche | Sélectionne l’objet (détails, emplacements, Localiser) |
| Double-clic ou Maj+clic | Retire une stack (64, 16 ou 1 selon l’objet) |
| Clic droit | Retire une demi-stack |
| Ctrl+clic | Retire un seul objet |
| Clic molette | Remplit l’inventaire avec cet objet |

Le champ **Quantité** (1 à 2 304), validé par Entrée ou **Prendre**, retire un nombre
libre. Tout retrait est borné par le stock et les emplacements libres de l’inventaire
du joueur. Les objets sont réellement enlevés des coffres, et l’index est mis à jour
après chaque transfert. Dans la vue regroupée, un objet présent en plusieurs variantes
fournit sa variante sans composants ; s’il n’en a pas, la vue Variantes s’ouvre pour
choisir précisément l’objet.

### Avec JEI (optionnel)

Storage fonctionne sans JEI. Si JEI est installé côté client :

- **R** / **U** sur un objet de la grille ouvre ses recettes et usages ;
- le bouton **⇄** à droite de la recherche synchronise la recherche du Terminal avec
  celle de JEI (`#tag` du Terminal devient `$tag` dans JEI) ;
- le bouton **+** d’une recette, Terminal ouvert, récupère dans l’inventaire les
  ingrédients qui manquent (Maj+**+** : autant de fabrications qu’une stack de chaque
  ingrédient le permet). Les ingrédients déjà portés sont comptés, les variantes
  renommées ou enchantées ne sont jamais utilisées implicitement, et les emplacements
  introuvables dans le réseau sont surlignés en rouge.

EMI n’a pas d’intégration dédiée. Le Terminal mesure 380 px de large : à une grande
échelle d’interface, JEI peut manquer de place pour afficher sa liste à côté.

La permission HomeCore CONTROL est nécessaire. Un inventaire joueur plein ne consomme
aucun objet. Un double coffre à cheval sur deux chunks fournit ses 54 emplacements si
les deux moitiés sont chargées ; si une moitié est déchargée, le double coffre entier
est indisponible. Détruire une moitié transforme normalement l’autre en coffre simple.

## Organiser et surveiller

L’écran du **Controller** (clic droit normal) est le panneau de gestion. Le Terminal
n’y donne pas accès : il sert seulement à trouver et prendre des objets. Ce panneau permet de renommer le Controller ou un inventaire, de créer une
zone et d’affecter un inventaire à cette zone. Un inventaire hors ligne peut être
oublié ; un inventaire encore présent dans un chunk couvert sera automatiquement
redécouvert. Les noms ne servent jamais d’identité technique : chaque Controller,
nœud et inventaire garde un UUID stable.

Le remplissage compte les slots occupés, même lorsqu’une pile est incomplète. Le
Controller produit un signal comparateur entre 0 et 15 selon la part de slots occupés.
Les données d’un inventaire inaccessible sont retirées des totaux, mais son état et
son dernier emplacement restent visibles. Les vérifications sont étalées dans le temps.

## HomeCore et permissions

Le Controller s’enregistre comme appareil dans un vrai HomeNetwork. Le partage emploie
les membres et permissions HomeCore : VIEW pour consulter et localiser, CONTROL pour
rafraîchir et retirer des objets, CONFIGURE pour associer et modifier. Les Metrics publient la capacité, le
nombre d’objets, les identités d’items, les inventaires et les inventaires pleins.

Les événements signalent les seuils et les changements de disponibilité sans spam :
ils ne se réarment qu’après une baisse définie par l’hystérésis. HomeLink Storage ne
dépend pas de HomeLink Dashboard ; un Dashboard peut consommer l’appareil de façon
générique via HomeCore.

## Configuration serveur

Fichier du monde : `serverconfig/homelink_storage-server.toml`.

| Réglage | Valeur par défaut |
|---|---:|
| Vérification des connexions | 40 ticks |
| Vérification complète échelonnée | 100 ticks |
| Inventaires scannés par tick | 4 |
| Inventaires par Controller | 256 |
| Nœuds Link/Repeater par Controller | 64 |
| Candidats inspectés par découverte | 4 096 |
| Slots par inventaire | 4 096 |
| Variantes par index | 16 384 |
| Durée de localisation | 200 ticks |
| Lignes par fragment réseau | 16 |
| Taille maximale du fragment | 65 536 octets |
| Composants visuels par variante | 8 192 octets |
| Seuil d’avertissement | 90 % |
| Seuil plein | 100 % |
| Hystérésis | 5 points |

Si la limite de candidats est atteinte, le Controller passe en avertissement et garde
les entrées déjà connues au lieu de les supprimer sur la base d’une découverte
incomplète. Un inventaire dépassant une limite d’index est signalé inaccessible plutôt
que compté partiellement.

## English quick start

Install HomeCore 1.13.0, HomeLink Energy 0.5.0 and HomeLink Storage 1.3.0 on both sides. Place a Controller,
then a Link in the chunk containing your inventories. Sneak-use the Controller with
an empty hand, then bind the Link, Repeaters and Terminal. Each active Repeater covers
one cardinally adjacent chunk in a connected chain. Open the Terminal to search,
manage zones and locate inventories; use the `?` button for the in-game manual.
Take 1 / Take 64 withdraw real objects from the selected inventory with CONTROL permission.
Storage never forces chunks to load.

## Voir la zone d’action d’un Connecteur ou d’un Répéteur

Un clic droit (sans s’accroupir) sur un Connecteur (Storage Link) ou un Répéteur affiche
son état dans la barre d’action et dessine sa **zone d’action** pendant 30 secondes : le
chunk entier, sur toute sa hauteur, entouré d’une clôture lumineuse, avec un quadrillage
au sol et un cadre autour du bloc. Les zones des autres nœuds du même réseau sont aussi
dessinées, ce qui montre la chaîne de Répéteurs. Un second clic droit sur le même bloc
masque la zone immédiatement.

| Couleur | Signification |
| --- | --- |
| Cuivre | Bloc cliqué, actif |
| Vert | Autre nœud actif du réseau |
| Rouge | Nœud hors ligne (non relié, chaîne coupée ou chunk déchargé) |

L’affichage est local : les autres joueurs ne le voient pas. Seules les positions des
nœuds et de leurs chunks sont transmises, jamais le contenu des inventaires.

## Objets sans destination : débordement et attente

Un objet déposé dans un Deposit va dans un coffre qui contient déjà la même variante.
S'il n'y en a aucun, il part après 5 s dans un **Coffre de débordement** du réseau
(recette dans [RECIPES.md](RECIPES.md)). Sans débordement, ou s'il est plein, l'objet
attend dans le Deposit : le Terminal affiche « ⚠ N en attente », et l'entrée **En attente
(Deposit)** du bouton de zones liste ces objets. On les reprend avec les mêmes gestes que
les autres objets. Le coffre de débordement n'a pas d'écran : son contenu se retire
depuis le Terminal, et un clic droit indique son remplissage.
