# AutoMod — framework Fabric client (Minecraft 26.1.2)

Framework modulaire d'assistance côté client ; premier module : **Voidgloom**.

## Versions (vérifiées sur maven.fabricmc.net / meta.fabricmc.net)
| Élément | Valeur |
|---|---|
| Minecraft | 26.1.2 (non obfusqué : noms Mojang, Intermediary `0.0.0`) |
| Fabric Loader | 0.19.3 |
| Fabric API | 0.155.3+26.1.2 (dernière build publiée pour 26.1.2) |
| Fabric Loom | 1.18-SNAPSHOT (plugin `net.fabricmc.fabric-loom`) |
| Java | 25 (requis par 26.1) — Gradle 9.7.1 (wrapper fourni) |

## Build
```
JAVA_HOME=<jdk25> ./gradlew build     # jar dans build/libs/
```

## Touches (catégorie « AutoMod »)
- `V` : activer/désactiver le module Voidgloom
- `I` : écrit dans le chat et copie dans le presse-papiers le bloc/l'entité visé(e)
- `END` : arrêt d'urgence (stopAll + désactive tous les modules)

## Architecture
```
core/       Framework, TickManager, TaskManager/Task, StateMachine, SafetyManager, ModuleManager, PlayerState, Debug
input/      InputController        (seul écrivain des touches, modèle « intention par tick »)
movement/   RotationController, MovementController, PositionController, PathController (A*), Walkability
targeting/  TargetSelector, EntityDetector, TargetInfo
combat/     CombatController
scoreboard/ ScoreboardReader, SlayerDetector
task/       LookAt, MoveToPosition, FollowTarget, AttackTarget, StopMovement
modules/slayer/voidgloom/  VoidgloomModule, VoidgloomTarget, GroundMechanicDetector, VoidgloomState
config/     ModConfig (config/automod.json)
```
Un nouveau module = une classe `extends AbstractModule` enregistrée dans `AutoModClient` ; il réutilise les services du `Framework`.

## Limite connue
La nature de la « mécanique au sol » du Voidgloom dépend du serveur et n'est pas déterminable depuis Minecraft/Fabric seuls.
Le détecteur par défaut cherche un **bloc** (`mechanicBlockId`, défaut `minecraft:beacon`) : à confirmer en jeu
(F3 + visée du bloc), puis ajuster la config. Si c'est une entité ou une particule, seul `GroundMechanicDetector` est à remplacer.
