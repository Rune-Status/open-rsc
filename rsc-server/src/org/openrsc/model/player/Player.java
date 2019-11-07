package org.openrsc.model.player;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import org.jboss.netty.channel.Channel;
import org.openrsc.Config;
import org.openrsc.model.Constants;
import org.openrsc.model.GameMode;
import org.openrsc.model.Mob;
import org.openrsc.model.Npc;
import org.openrsc.model.NpcManager;
import org.openrsc.model.PlayerManager;
import org.openrsc.model.Privilege;
import org.openrsc.net.packet.Packet;
import org.openrsc.net.packet.PacketManager;
import org.openrsc.util.GameUtils;

public class Player extends Mob {

    // Packet Handling / Network
    private final Channel channel;
    private final BlockingQueue<Packet> packetQueue = new LinkedBlockingQueue<>();
    private final PacketDispatcher packetDispatcher;
    private long lastPacketReceived;

    /**
     * The account id is an incremental value created by the database. It is
     * permanent and will transfer between sessions.
     */
    private int accountId;

    /**
     * The session id is an incremental value generated by the server. It is a
     * temporary value that regenerates upon login.
     */
    private int sessionId;

    /**
     * The account display name.
     */
    private final String displayName;

    /**
     * The account privilege.
     */
    private Privilege privilege;

    /**
     * The user's game mode.
     */
    private GameMode gameMode;

    /**
     */
    private boolean isMuted = false;

    /**
     * A list of nearby entities.
     * The mob will only know about the existence of these entities.
     */
    private Set<Player> localPlayerList = new HashSet<>();
    private final Queue<Player> playerRemovalQueue = new LinkedList<>();
    private Set<Npc> localNpcList = new HashSet<>();
    private final Queue<Npc> npcRemovalQueue = new LinkedList<>();

    private long lastYellTime = 0L;

    // TODO Appearance
    public int colourHairType;
    public int colourTopType;
    public int colourBottomType;
    public int colourSkinType;

    /**
     * Npc pet slot.
     */
    private Npc familiar;

    public Player(Channel channel, int accountId, int sessionId, String displayName) {
        super(sessionId, Constants.DEFAULT_LOCATION.getX(), Constants.DEFAULT_LOCATION.getZ());
        this.accountId = accountId;
        this.sessionId = sessionId;
        this.displayName = displayName;
        this.channel = channel;
        channel.setAttachment(this);
        this.packetDispatcher = new PacketDispatcher(this);
        this.lastPacketReceived = GameUtils.getCurrentTimeMillis();
    }

    @Override
    public void tick(long currentTime, Set<Player> globalPlayerList, Set<Npc> globalNpcList) {
        updateLocalList(globalPlayerList, globalNpcList);
        
        // Execute the queued packets.
        List<Packet> toProcess = new ArrayList<>();
        packetQueue.drainTo(toProcess);
        for (Packet packet : toProcess) {
            PacketManager.execute(this, packet);
            packetQueue.remove(packet);
        }

        // Idle logout timer.
        if (currentTime - lastPacketReceived > (60_000 * Config.get().idleDisconnect())) {
            System.out.println("[" + displayName + "]Disconnected - Idle logout.");
            PlayerManager.getInstance().queueLogout(this);
            return;
        }

    }

    @Override
    public boolean isDead() {
        return false;
    }

    /**
     * Loop through each entity in the world and create a list of nearby entities.
     */
    private void updateLocalList(Set<Player> globalPlayerList, Set<Npc> globalNpcList) {
        boolean isLocal;

        // Check if an entity has been unregistered from the server.
        for (Player player : localPlayerList) {
            if (!globalPlayerList.contains(player)) {
                playerRemovalQueue.add(player);
                continue;
            }
        }

        // Check for new entities that should be added to the local list.
        for (Player player : globalPlayerList) {
            isLocal = getLocation().getDistance(player.getLocation()) < Constants.MAXIMUM_INTERACTION_DISTANCE;

            // Register a new entity.
            if (isLocal && !localPlayerList.contains(player)) {
                localPlayerList.add(player);
                continue;
            }

            // Unregister a entity.
            if (!isLocal && localPlayerList.contains(player)) {
                playerRemovalQueue.add(player);
                continue;
            }
        }

        // Merge the list changes.
        while (!playerRemovalQueue.isEmpty()) {
            localPlayerList.remove(playerRemovalQueue.poll());
        }


        // Check if an entity has been unregistered from the server.
        for (Npc npc : localNpcList) {
            if (!globalNpcList.contains(npc)) {
                npcRemovalQueue.add(npc);
                continue;
            }
        }

        // Check for new entities that should be added to the local list.
        for (Npc npc : globalNpcList) {
            isLocal = getLocation().getDistance(npc.getLocation()) < Constants.MAXIMUM_INTERACTION_DISTANCE;

            // Register a new entity.
            if (isLocal && !localNpcList.contains(npc)) {
                localNpcList.add(npc);
                continue;
            }

            // Unregister a entity.
            if (!isLocal && localNpcList.contains(npc)) {
                npcRemovalQueue.add(npc);
                continue;
            }
        }

        // Merge the list changes.
        while (!npcRemovalQueue.isEmpty()) {
            localNpcList.remove(npcRemovalQueue.poll());
        }
    }

    /**
     * Executed when the player gets registered.
     */
    public void executeLogin() {
        super.setLocation(Constants.DEFAULT_LOCATION);
        super.setTravelBack();
        packetDispatcher.sendGameMessage("Welcome to RuneScape.");
    }

    /**
     * Executed when the player gets unregistered.
     */
    public void executeLogout() {
        packetQueue.clear();
        interrupt();
        if (familiar != null) {
            NpcManager.getInstance().unregister(familiar);
        }
        if (channel.isConnected()) {
            channel.disconnect();
        }
    }

    /**
     * Queues an incoming packet to be executed in the next game tick.
     */
    public void addQueuedPacket(Packet packet) {
        this.packetQueue.add(packet);
    }

    /**
     */
    public void interrupt() {
        // Reset queued actions.
    }

    /**
     * A cooldown check for the /yell command.
     *
     * @return False if there is no cooldown.
     */
    public boolean hasYellThrottle() {
        if (isDeveloper() || isModerator()) {
            return false;
        }
        long currentTimeMillis = GameUtils.getCurrentTimeMillis();
        if (currentTimeMillis - lastYellTime > 15_000) {
            lastYellTime = currentTimeMillis;
            return false;
        }
        return true;
    }
    
    /**
     * The list of players available to this mob.
     */
    public Set<Player> getLocalPlayers() {
        return localPlayerList;
    }

    /**
     * The list of npc available to this mob.
     */
    public Set<Npc> getLocalNpcs() {
        return localNpcList;
    }

    public Channel getChannel() {
        return channel;
    }

    public PacketDispatcher getPacketDispatcher() {
        return packetDispatcher;
    }

    public void updateLastPacketReceivedTime() {
        this.lastPacketReceived = GameUtils.getCurrentTimeMillis();
    }

    public int getAccountId() {
        return accountId;
    }

    @Override
    public int getSessionId() {
        return sessionId;
    }

    public String getDisplayName() {
        return displayName;
    }

    public Privilege getPrivileges() {
        return privilege;
    }

    public void setPrivileges(Privilege privilege) {
        this.privilege = privilege;
    }

    /**
     * @return True, if the account has developer privileges.
     */
    public boolean isDeveloper() {
        return privilege == Privilege.GITHUB_CONTRIBUTOR || privilege == Privilege.ADMINISTRATOR
                || privilege == Privilege.ROOT;
    }

    /**
     * @return True, if the account has moderator privileges.
     */
    public boolean isModerator() {
        return privilege != Privilege.REGULAR;
    }

    public GameMode getGameMode() {
        return gameMode;
    }

    /**
     * @return True, if chatbox communication privileges are suspended.
     */
    public boolean isMuted() {
        return isMuted;
    }

}