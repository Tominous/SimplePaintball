package com.borgdude.paintball.objects;

import com.borgdude.paintball.Main;
import com.borgdude.paintball.managers.PaintballManager;

import net.milkbowl.vault.economy.EconomyResponse;

import org.bstats.bukkit.Metrics;
import org.bukkit.*;
import org.bukkit.block.Sign;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarFlag;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.Callable;

public class Arena {

    private String title;
    private Location endLocation;
    private Location lobbyLocation;
    private Team blueTeam;
    private Team redTeam;
    private int minPlayers;
    private int maxPlayers;
    private ArrayList<Sign> signs;
    private ArenaState arenaState;
    private Set<UUID> players;
    private Set<UUID> spectators;
    private HashMap<UUID, Integer> kills;
    private int timer;
    private BossBar bossBar;
    private int totalTime;
    private Main plugin;
    private boolean activated;
    private Scoreboard board;
    private HashMap<UUID, Gun> gunKits;
    private HashMap<UUID, Integer> spawnTimer = null;
    
    private PaintballManager paintballManger = Main.paintballManager;

    public Arena(String name, Main plugin) {
        this.title = name;
        players = new HashSet<>();
        setSpectators(new HashSet<>());
        kills = new HashMap<>();
        arenaState = ArenaState.WAITING_FOR_PLAYERS;
        signs = new ArrayList<>();
        setGunKits(new HashMap<>());
        setSpawnTimer(new HashMap<>());
        this.plugin = plugin;
    }

    public BossBar getBossBar() {
        return bossBar;
    }

    private void updateBossbar() {
        if (bossBar == null) {
            bossBar = Bukkit.createBossBar("TEMP", BarColor.BLUE, BarStyle.SOLID, new BarFlag[0]);
            bossBar.setVisible(true);
        }

        bossBar.setVisible(true);

        Bukkit.getScheduler().runTaskAsynchronously(plugin, new Runnable() {
            @Override
            public void run() {
                String m = String.valueOf(getTimer() / 60);
                String s = String.valueOf(getTimer() % 60);
                if (Integer.valueOf(s) < 10) {
                    s = "0" + s;
                }

                double progress = (getTimer() / (double) getTotalTime());
                bossBar.setTitle(m + ":" + s);
                bossBar.setProgress(progress);

                for (UUID id : getPlayers()) {
                    Player p = Bukkit.getPlayer(id);
                    if (p != null) {
                        bossBar.addPlayer(p);
                    }
                }
            }
        });
    }

    private void removeBossbar() {
        if (bossBar == null) return;

        for (UUID id : getPlayers()) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                bossBar.removePlayer(p);
            }
        }
    }

    public int getTotalTime() {
        return totalTime;
    }

    public void setTotalTime(int totalTime) {
        this.totalTime = totalTime;
        this.timer = totalTime;
    }

    public ArenaState getArenaState() {
        return arenaState;
    }

    public void setArenaState(ArenaState arenaState) {
        this.arenaState = arenaState;
    }

    public int getTimer() {
        return timer;
    }

    public void setTimer(int timer) {
        this.timer = timer;
    }

    public void setActivated(boolean activated) {
        this.activated = activated;
    }

    public ArrayList<Sign> getSigns() {
        return signs;
    }

    public void setSigns(ArrayList<Sign> signs) {
        this.signs = signs;
    }

    public void updateSigns() {
        // int i = 0;
        for (Sign s : getSigns()) {
            s.setLine(0, ChatColor.BLUE + "[PaintBall]");
            s.setLine(1, ChatColor.GREEN + getTitle());
            s.setLine(2, ChatColor.RED + getArenaState().getFormattedName());
            s.setLine(3, String.valueOf(getPlayers().size()) + "/" + String.valueOf(getMaxPlayers()));
            s.update(true);
            i++;
            Bukkit.getConsoleSender().sendMessage("Updated sign number " + i + "for arena " + getTitle());
        }
    }

    public void checkToStart(){
        if(arenaState.equals(ArenaState.STARTING))
            return;

        updateSigns();

        if (getPlayers().size() >= minPlayers) {
            Bukkit.getConsoleSender().sendMessage("Starting arena " + getTitle());
            setArenaState(ArenaState.STARTING);
            setTotalTime(getLobbyTime());
            BukkitRunnable runnable = new BukkitRunnable() {
                @Override
                public void run() {
                    updateSigns();

                    if (getPlayers().size() < minPlayers) {
                        setArenaState(ArenaState.WAITING_FOR_PLAYERS);
                        bossBar.setVisible(false);
                        cancel();
                    }

                    if (getTimer() % 5 == 0) {
                        for (UUID id : getPlayers()) {
                            Player p = Bukkit.getPlayer(id);
                            if (p != null) {
                                p.sendMessage(ChatColor.AQUA + "Game starting in " + getTimer() + " seconds.");
                            }
                        }
                    }

                    updateBossbar();
                    setTimer(getTimer() - 1);

                    if (getTimer() == 0) {
                        startGame();
                        cancel();
                    }
                }
            };

            runnable.runTaskTimer(this.plugin, 20, 20);
        } else {
            for (UUID id : getPlayers()) {
                Bukkit.getServer().getPlayer(id).sendMessage(ChatColor.YELLOW + "Not starting, not enough people.");
            }
        }
    }

    private int getLobbyTime() {
		int time = plugin.getConfig().getInt("lobby-time");
		if(time <= 5) {
			time = 60;
			System.out.println("Invalid lobby time, please configure an integer greater than 5");
		}
		return time;
	}

	public void startGame() {
        setArenaState(ArenaState.IN_GAME);
        addMetrics();
        assignTeams();
        blueTeam.giveArmor(Color.BLUE);
        redTeam.giveArmor(Color.RED);
        spawnPlayers();
        generateKills();
        setListNames();
        setGameScoreboard();
        setTotalTime(getGameTime());
        BukkitRunnable runnable = new BukkitRunnable() {
            @Override
            public void run() {

                updateSigns();

                if (blueTeam.getMembers().size() == 0) {
                    stopGame(redTeam);
                    cancel();
                }

                if (redTeam.getMembers().size() == 0) {
                    stopGame(blueTeam);
                    cancel();
                }

                if (getTimer() == getTotalTime()) {
                    for (UUID id : getPlayers()) {
                        Bukkit.getServer().getPlayer(id).sendMessage(ChatColor.GREEN + "Game has started!");
                    }
                }

                setTimer(getTimer() - 1);

                if (getTimer() % 60 == 0 && getTimer() != 0) {
                    for (UUID id : getPlayers()) {
                        Bukkit.getServer().getPlayer(id).sendMessage(ChatColor.GREEN + "There are " + ChatColor.AQUA +
                                String.valueOf(getTimer() / 60) + " minutes " + ChatColor.GREEN + "left.");
                    }
                } else if (getTimer() == 30 || getTimer() == 15 || getTimer() <= 5) {
                    for (UUID id : getPlayers()) {
                        Bukkit.getServer().getPlayer(id).sendMessage(ChatColor.GREEN + "There are " + ChatColor.AQUA +
                                String.valueOf(getTimer()) + " seconds" + ChatColor.GREEN + " left.");
                    }
                }
                
                decreaseSpawnTime();
                updateBossbar();

                if (getTimer() == 0) {
                    stopGame();
                    cancel();
                }
            }

        };

        runnable.runTaskTimer(this.plugin, 20, 20);
    }

    private void decreaseSpawnTime() {
		for(Map.Entry<UUID, Integer> entry : getSpawnTimer().entrySet()) {
			int time = entry.getValue() - 1;
			if(time < 0) {
				getSpawnTimer().remove(entry.getKey());
				Bukkit.getPlayer(entry.getKey()).sendMessage(ChatColor.YELLOW + "You're not protected anymore");
			}
			else
				getSpawnTimer().replace(entry.getKey(), time);
		}
		
	}

	private void addMetrics() {
    	Main.metrics.addCustomChart(new Metrics.SingleLineChart("games_played", () -> {return 1;} ));
    }

	private int getGameTime() {
		int time = plugin.getConfig().getInt("game-time");
		if(time < 30) {
			time = 240;
			System.out.println("Invalid game time, please configure an integer greater than 30");
		}
		return time;
	}

	private void setGameScoreboard(){
        for(UUID id : getPlayers()){
            Player p = Bukkit.getPlayer(id);
            if (p != null){
                board = Bukkit.getScoreboardManager().getNewScoreboard();
                Objective obj = board.registerNewObjective("Paintball", "dummy");
                obj.setDisplayName("Paintball");
                obj.setDisplaySlot(DisplaySlot.SIDEBAR);

                org.bukkit.scoreboard.Team blueKillCounter = board.registerNewTeam("blueKillCounter");
                blueKillCounter.addEntry(ChatColor.BLACK + "" + ChatColor.BLUE + "");
                blueKillCounter.setPrefix("Blue " + plugin.getConfig().getString("In-Game.Kills").toLowerCase() + ": "  + "0");

                obj.getScore(ChatColor.BLACK + "" + ChatColor.BLUE + "").setScore(15);

                org.bukkit.scoreboard.Team redTeamCounter = board.registerNewTeam("redKillCounter");
                redTeamCounter.addEntry(ChatColor.BLACK + "" + ChatColor.RED + "");
                redTeamCounter.setPrefix("Red " + plugin.getConfig().getString("In-Game.Kills").toLowerCase() + ": " + "0");

                obj.getScore(ChatColor.BLACK + "" + ChatColor.RED + "").setScore(14);

                p.setScoreboard(board);

            }
        }
    }

    public void updateScoreboard(){
        Bukkit.getConsoleSender().sendMessage("Updated scoreboard for arena...");
        int blueKills = getTotalTeamKills(getBlueTeam());
        int redKills = getTotalTeamKills(getRedTeam());
        Bukkit.getScheduler().runTaskAsynchronously(plugin, new Runnable() {
            @Override
            public void run() {
                for(UUID id : getPlayers()){
                    Player p = Bukkit.getPlayer(id);
                    if (p != null){
                        Scoreboard board = p.getScoreboard();

                        board.getTeam("blueKillCounter").setPrefix("Blue " + plugin.getConfig().getString("In-Game.Kills").toLowerCase() + ": " + String.valueOf(blueKills));

                        board.getTeam("redKillCounter").setPrefix("Red "+ plugin.getConfig().getString("In-Game.Kills").toLowerCase() + ": " + String.valueOf(redKills));
                    }
                }
            }
        });
    }

    public void assignTeams() {
        String team;
        Random random = new Random();
        // if both teams have the same amount of players....
        for(UUID id : getPlayers()){
            Player p = Bukkit.getServer().getPlayer(id);
            p.getInventory().clear();
            p.addPotionEffect(new PotionEffect(PotionEffectType.SATURATION, plugin.getConfig().getInt("game-time") * 20, 5, true));
            p.setLevel(0);

            if (redTeam.getMembers().size() == blueTeam.getMembers().size()) {
                // add to a random team
                if (random.nextBoolean()) {
                    // add to red
                    redTeam.addMember(p);
                    team = "&c&lRed";
                } else {
                    // add to blue
                    blueTeam.addMember(p);
                    team = "&b&lBlue";
                }
            } else {
                // add to the team with the smaller amount of players
                if (redTeam.getMembers().size() < blueTeam.getMembers().size()) {
                    // add to red
                    redTeam.addMember(p);
                    team = "&c&lRed";
                } else {
                    // add to blue
                    blueTeam.addMember(p);
                    team = "&b&lBlue";
                }
            }
            p.sendMessage(ChatColor.translateAlternateColorCodes('&', "&aYou are on the " + team + " &ateam."));
        }

    }

    public void spawnPlayers() {
        for (UUID id : getPlayers()) {
            Player p = Bukkit.getPlayer(id);

            Gun gun = getGunKits().get(p.getUniqueId());
            if(gun == null) {
            	System.out.println(p.getName() + "'s kit was null");
            	gun = paintballManger.getGunByName("Regular");
            }
            ItemStack is = gun.getInGameItem();
            p.getInventory().addItem(is);
            
            // Add leave bed
            ItemStack bed = new ItemStack(Material.WHITE_BED);
            ItemMeta im = bed.getItemMeta();
            im.setDisplayName(ChatColor.AQUA + "Leave Arena");
            bed.setItemMeta(im);
            p.getInventory().setItem(9, bed);

            Team team = getPlayerTeam(p);
            p.teleport(team.getRandomLocation());
        }
    }

    public void setListNames() {
        for (UUID pID : redTeam.getMembers()) {
            Player p = Bukkit.getPlayer(pID);
            if (p != null) {
                p.setDisplayName(ChatColor.RED + p.getName() + ChatColor.RESET);
                p.setPlayerListName(ChatColor.RED + p.getName() + ChatColor.RESET);
            }
        }
        for (UUID pID : blueTeam.getMembers()) {
            Player p = Bukkit.getPlayer(pID);
            if (p != null) {
                p.setDisplayName(ChatColor.BLUE + p.getName() + ChatColor.RESET);
                p.setPlayerListName(ChatColor.BLUE + p.getName() + ChatColor.RESET);
            }
        }
    }

    public void generateKills() {
        getKills().clear();
        for (UUID id : getPlayers()) {
            getKills().put(id, 0);
        }
    }

    public void stopGame(Team team) {
        setArenaState(ArenaState.RESTARTING);
        updateSigns();
        announceWinner(team);
        kickPlayers();
        blueTeam.getMembers().clear();
        redTeam.getMembers().clear();
        removeScoreboard();
        setArenaState(ArenaState.WAITING_FOR_PLAYERS);
        updateSigns();
    }

    public void stopGame() {
        setArenaState(ArenaState.RESTARTING);
        updateSigns();
        announceWinner();
        kickPlayers();
        blueTeam.getMembers().clear();
        redTeam.getMembers().clear();
        removeScoreboard();
        setArenaState(ArenaState.WAITING_FOR_PLAYERS);
        updateSigns();
    }
    
    public void awardWinners(Team team) {
    	if(Main.econ == null) return;
    	
    	int amount = plugin.getConfig().getInt("Economy.Reward-Team");
    	
    	if(amount == 0) return;
    	
    	if(team == null) {
    		for (UUID id : getPlayers()) {
                Player p = Bukkit.getPlayer(id);
                EconomyResponse r = Main.econ.depositPlayer(p, amount/2);
                if(r.transactionSuccess()) {
                    p.sendMessage(String.format("You were given %s and now have %s", Main.econ.format(r.amount), Main.econ.format(r.balance)));
                } else {
                    p.sendMessage(String.format("An error occured: %s", r.errorMessage));
                }
            }
    	} else {
    		for (UUID id : team.getMembers()) {
    			Player p = Bukkit.getPlayer(id);
                EconomyResponse r = Main.econ.depositPlayer(p, amount);
                if(r.transactionSuccess()) {
                    p.sendMessage(String.format("You were given %s and now have %s", Main.econ.format(r.amount), Main.econ.format(r.balance)));
                } else {
                    p.sendMessage(String.format("An error occured: %s", r.errorMessage));
                }
    		}
    	}
    }

    public void announceWinner(Team team) {
        if (team.equals(blueTeam)) {
            int bKills = getTotalTeamKills(blueTeam);
            for (UUID id : getPlayers()) {
                Player p = Bukkit.getPlayer(id);
                p.sendMessage(ChatColor.BLUE + "Blue " + ChatColor.GREEN + " wins by default with " + ChatColor.BLUE +
                        bKills + " " + plugin.getConfig().getString("In-Game.Kills").toLowerCase() + "!");
            }
        } else if (team.equals(redTeam)) {
            int rKills = getTotalTeamKills(redTeam);
            for (UUID id : getPlayers()) {
                Player p = Bukkit.getPlayer(id);
                p.sendMessage(ChatColor.RED + "Red " + ChatColor.GREEN + " wins by default with " + ChatColor.RED +
                        rKills +  " " + plugin.getConfig().getString("In-Game.Kills").toLowerCase() + "!");
            }
        } else {
            return;
        }
    }

    public void announceWinner() {
        int bKills = getTotalTeamKills(blueTeam);
        int rKills = getTotalTeamKills(redTeam);
        Team winningTeam = null;
        for (UUID id : getPlayers()) {
            Player p = Bukkit.getPlayer(id);
            if (bKills > rKills) {
                p.sendMessage(ChatColor.BLUE + "Blue team " + ChatColor.GREEN + "won with " + ChatColor.BLUE +
                        bKills + " " + plugin.getConfig().getString("In-Game.Kills").toLowerCase() + ".");
                winningTeam = blueTeam;
            } else if (bKills == rKills) {
                p.sendMessage(ChatColor.AQUA + "It was a tie with: " + bKills + " " + plugin.getConfig().getString("In-Game.Kills").toLowerCase() + ".");
            } else {
                p.sendMessage(ChatColor.RED + "Red team " + ChatColor.GREEN + "won with " + ChatColor.RED +
                        rKills + " " + plugin.getConfig().getString("In-Game.Kills").toLowerCase() + ".");
                winningTeam = redTeam;
            }
        }
        awardWinners(winningTeam);
    }

    private void removeScoreboard(){
        for(UUID id : getPlayers()){
            Player p = Bukkit.getPlayer(id);
            p.setScoreboard(Bukkit.getScoreboardManager().getNewScoreboard());
        }
        Bukkit.getConsoleSender().sendMessage("Removed scoreboards");
    }
    
    public void setGunKit(Player player, Gun gun) {
    	UUID id = player.getUniqueId();
    	if(getGunKits().containsKey(id)) {
    		getGunKits().replace(id, gun);
    	} else {
    		getGunKits().put(id, gun);
    	}
    	player.sendMessage(ChatColor.AQUA + "You're now using the " + ChatColor.GREEN + gun.getName());
    }
    
    private void removeScoreboard(Player player) {
    	player.setScoreboard(Bukkit.getScoreboardManager().getNewScoreboard());
    }

    public void kickPlayers() {
        for (UUID id : getPlayers()) {
            Player p = Bukkit.getPlayer(id);
            p.removePotionEffect(PotionEffectType.SATURATION);
            p.teleport(getEndLocation());
           	restoreInventory(p);
            p.setPlayerListName(ChatColor.RESET + p.getName());
            p.setDisplayName(ChatColor.RESET + p.getName());
            removeScoreboard(p);
        }
        
        for (UUID id : getSpectators()) {
        	Player p = Bukkit.getPlayer(id);
        	p.teleport(getEndLocation());
        	p.setGameMode(Bukkit.getDefaultGameMode());
        }
        
        removeBossbar();
        
        Main.metrics.addCustomChart(new Metrics.AdvancedPie("guns_used", new Callable<Map<String,Integer>>() {
			
			@Override
			public Map<String, Integer> call() throws Exception {
				Map<String, Integer> valueMap = new HashMap<>();
				for(Gun gun : getGunKits().values()) {
					String name = gun.getName();
					if(valueMap.containsKey(name)) {
						int v = valueMap.get(name);
						valueMap.put(name, v + 1);
					} else {
						valueMap.put(name, 1);
					}
				}
				return valueMap;
			}
		}));
        
        getGunKits().clear();
        getPlayers().clear();
        getSpectators().clear();
        getKills().clear();
    }

    public void removePlayerInGame(Player p) {
        Team team = getPlayerTeam(p);
        team.getMembers().remove(p.getUniqueId());
        getPlayers().remove(p.getUniqueId());
        getKills().remove(p.getUniqueId());
        p.teleport(getEndLocation());
        p.setGameMode(Bukkit.getDefaultGameMode());
        p.removePotionEffect(PotionEffectType.SATURATION);
        restoreInventory(p);
        p.sendMessage(ChatColor.GREEN + "You have left the game and teleported to the start.");
        p.setPlayerListName(ChatColor.RESET + p.getName());
        p.setDisplayName(ChatColor.RESET + p.getName());
        removeScoreboard(p);
        if (bossBar != null) {
            bossBar.removePlayer(p);
        }
        updateSigns();
    }

    private void restoreInventory(Player p) {
    	p.getInventory().clear();
    	try {
			Main.inventoryManager.restoreInventory(p);
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	public boolean isActivated() {
        return activated;
    }

    public void setActivated(Player player) {
        if (endLocation == null) {
            player.sendMessage(ChatColor.RED + "No end location set. Use: /pb set end");
            return;
        }

        if (lobbyLocation == null) {
            player.sendMessage(ChatColor.RED + "No lobby location set. Use: /pb set lobby");
            return;
        }

        if (blueTeam.getSpawnLocations().size() == 0) {
            player.sendMessage(ChatColor.RED + "No blue team spawn locations set. Use: /pb set blue");
            return;
        }

        if (redTeam.getSpawnLocations().size() == 0) {
            player.sendMessage(ChatColor.RED + "No red team spawn locations set. Use: /pb set red");
            return;
        }

        activated = true;
        player.sendMessage(ChatColor.GREEN + "Arena " + ChatColor.AQUA + title + ChatColor.GREEN + " has been activated!");
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public Location getEndLocation() {
        return endLocation;
    }

    public void setEndLocation(Location endLocation) {
        this.endLocation = endLocation;
    }

    public Location getLobbyLocation() {
        return lobbyLocation;
    }

    public void setLobbyLocation(Location lobbyLocation) {
        this.lobbyLocation = lobbyLocation;
    }

    public Team getBlueTeam() {
        return blueTeam;
    }

    public void setBlueTeam(Team blueTeam) {
        this.blueTeam = blueTeam;
    }

    public Team getRedTeam() {
        return redTeam;
    }

    public void setRedTeam(Team redTeam) {
        this.redTeam = redTeam;
    }

    public int getMinPlayers() {
        return minPlayers;
    }

    public void setMinPlayers(int minPlayers) {
        this.minPlayers = minPlayers;
    }

    public int getMaxPlayers() {
        return maxPlayers;
    }

    public void setMaxPlayers(int maxPlayers) {
        this.maxPlayers = maxPlayers;
    }

    public Set<UUID> getPlayers() {
        return players;
    }

    public void setPlayers(Set<UUID> players) {
        this.players = players;
    }


    public Set<UUID> getSpectators() {
		return spectators;
	}

	public void setSpectators(Set<UUID> spectators) {
		this.spectators = spectators;
	}

	public HashMap<UUID, Integer> getKills() {
        return kills;
    }

    public void setKills(HashMap<UUID, Integer> kills) {
        this.kills = kills;
    }

    public Integer getTotalTeamKills(Team team) {
        int total = 0;

        for (UUID id : team.getMembers()) {
            if (!kills.containsKey(id))
                continue;

            total += kills.get(id);
        }

        return total;
    }

    public Team getPlayerTeam(Player player) {
        UUID pUUID = player.getUniqueId();
        for (UUID id : blueTeam.getMembers()) {
            if (id.equals(pUUID))
                return blueTeam;
        }
        for (UUID id : redTeam.getMembers()) {
            if (id.equals(pUUID))
                return redTeam;
        }

        return null;
    }

    public Integer getPlayerKills(Player player) {
        UUID pUUID = player.getUniqueId();
        if (kills.containsKey(pUUID))
            return kills.get(pUUID);

        return null;
    }

    public void respawnPlayer(Player player) {
        UUID pUUID = player.getUniqueId();
        Team team = getPlayerTeam(player);
        Location spawnLoc = team.getRandomLocation();
        player.teleport(spawnLoc);
    }

	public HashMap<UUID, Gun> getGunKits() {
		return gunKits;
	}

	public void setGunKits(HashMap<UUID, Gun> gunKits) {
		this.gunKits = gunKits;
	}

	public HashMap<UUID, Integer> getSpawnTimer() {
		return spawnTimer;
	}

	public void setSpawnTimer(HashMap<UUID, Integer> spawnTimer) {
		this.spawnTimer = spawnTimer;
	}
	
	public void addSpawnTime(Player p, int time) {
		if(getSpawnTimer().containsKey(p.getUniqueId()))
			return;
		
		getSpawnTimer().put(p.getUniqueId(), time);
		p.sendMessage(ChatColor.YELLOW + "You're protected for " + ChatColor.RED + time + ChatColor.YELLOW + " seconds");
	}

	public void checkMinMax() {
		if(getMinPlayers() < 2 || getMinPlayers() > getMaxPlayers()) {
			Bukkit.getServer().getConsoleSender().sendMessage("Value for min players for " + getTitle() + " is invalid, setting to 2");
			setMinPlayers(2);
		}
		
		if(getMaxPlayers() < getMinPlayers()) {
			Bukkit.getServer().getConsoleSender().sendMessage("Value for max players for " + getTitle() + " is invalid, setting to 10");
			setMaxPlayers(10);
		}
		
	}
}
