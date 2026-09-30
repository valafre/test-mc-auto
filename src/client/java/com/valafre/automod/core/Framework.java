package com.valafre.automod.core;

import com.valafre.automod.combat.CombatController;
import com.valafre.automod.humanize.Humanizer;
import com.valafre.automod.input.InputController;
import com.valafre.automod.movement.MovementController;
import com.valafre.automod.movement.PathController;
import com.valafre.automod.movement.PositionController;
import com.valafre.automod.movement.RotationController;
import com.valafre.automod.scoreboard.ScoreboardReader;
import com.valafre.automod.scoreboard.SlayerDetector;
import com.valafre.automod.targeting.EntityDetector;
import com.valafre.automod.targeting.EntityInfoResolver;
import com.valafre.automod.targeting.TargetSelector;
import net.minecraft.client.Minecraft;

/** Racine de composition : instancie et expose les services génériques partagés par tous les modules. */
public final class Framework {

	private final Minecraft mc;
	private final PlayerState playerState = new PlayerState();
	private final InputController input = new InputController();
	private final Humanizer humanizer = new Humanizer();
	private final RotationController rotation = new RotationController(humanizer);
	private final PathController paths = new PathController();
	private final MovementController movement = new MovementController(input, rotation, paths);
	private final PositionController positions = new PositionController(paths);
	private final TargetSelector targetSelector = new TargetSelector();
	private final EntityDetector entityDetector = new EntityDetector();
	private final EntityInfoResolver entityInfo = new EntityInfoResolver();
	private final CombatController combat;
	private final ScoreboardReader scoreboard = new ScoreboardReader();
	private final SlayerDetector slayer = new SlayerDetector(scoreboard);
	private final TaskManager tasks = new TaskManager(input);
	private final ModuleManager modules = new ModuleManager();
	private final SafetyManager safety = new SafetyManager(this);

	public Framework(Minecraft mc) {
		this.mc = mc;
		this.combat = new CombatController(mc);
	}

	public Minecraft minecraft() { return mc; }
	public PlayerState player() { return playerState; }
	public InputController input() { return input; }
	public Humanizer humanizer() { return humanizer; }
	public RotationController rotation() { return rotation; }
	public MovementController movement() { return movement; }
	public PositionController positions() { return positions; }
	public TargetSelector targetSelector() { return targetSelector; }
	public EntityDetector entityDetector() { return entityDetector; }
	public EntityInfoResolver entityInfo() { return entityInfo; }
	public CombatController combat() { return combat; }
	public ScoreboardReader scoreboard() { return scoreboard; }
	public SlayerDetector slayer() { return slayer; }
	public TaskManager tasks() { return tasks; }
	public ModuleManager modules() { return modules; }
	public SafetyManager safety() { return safety; }
}
