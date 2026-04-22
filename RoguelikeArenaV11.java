// RoguelikeArenaV11.java
// Uproszczona wersja: na każdym poziomie pojawia się ~40 wrogów.
// Dodano: trupy ('c') pozostające na podłodze po zabitych wrogach,
//       dropy mogą pojawiać się na trupach, trupy są zapisywane w savegame.
// Zmiany: dodano zapis i odczyt licznika przejść (timesCleared) w savegame,
//        skalowanie HP zwykłych wrogów w zależności od timesCleared, limit HP zwykłych wrogów = 100.
// DODANO: granaty (2 na misję), rzucane z animacją lotu i eksplozją 3x3, czasem drop po zabiciu.
// ZMIANA: eksplozja granatu używa sfx7.wav zamiast sfx6.wav
// ZMIANA: nie można rzucić dwóch granatów w jednej turze (rzut granatem zużywa jedno "shot" na turę)

import java.awt.Color;
import java.awt.Composite;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.AlphaComposite;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.beans.XMLDecoder;
import java.beans.XMLEncoder;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.Serializable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.HashMap;
import java.util.Map;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.LineEvent;
import javax.sound.sampled.LineListener;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

public class RoguelikeArenaV11 extends JPanel {

    // --- MAP ---
    private static final int MAP_W = 60;
    private static final int MAP_H = 60;

    private static final int FLOOR       = 0;
    private static final int WALL        = 1;
    private static final int DOOR_CLOSED = 2;
    private static final int DOOR_OPEN   = 3;

    private int[][] map = new int[MAP_H][MAP_W];

    // --- VIEWPORT ---
    private static final int VIEW_SIZE = 11;   // number of tiles across viewport
    private static final int TILE_PX   = 30;

    // --- BITMAP FONT ---
    private BufferedImage fontImage;
    private static final int GLYPH_W = 12;
    private static final int GLYPH_H = 12;
    private int GLYPH_COLS = 0;

    private static final String GLYPH_MAP =
            " !\"#$%&'()*+,-./" +
            "0123456789:;<=>?" +
            "@ABCDEFGHIJKLMNO" +
            "PQRSTUVWXYZ[\\]^_" +
            "`abcdefghijklmno" +
            "pqrstuvwxyz{|}~\u00A0" +
            "░▒▓█▲▼◄►♪♫©®✓✕" ;

    private final Map<Character, Integer> glyphOverrides = new HashMap<>();

    // --- GAME STATE ---
    private enum GameState { TITLE, MENU, PLAYING, AIMING, LOOK, ANIMATING, LOADING, GAME_OVER }
    private GameState state = GameState.TITLE;

    // --- MISSION ---
    private int mission = 1;
    private static final int MAX_MISSION = 8; // 8 levels
    private static final int ENEMIES_PER_MISSION = 40; // zawsze ~40 wrogów na misję

    // --- PLAYER ---
    private int px, py;
    private int hp = 100;
    private int maxHP = 100;
    private int playerSavedTile = FLOOR;
    private int playerSavedPickupIndex = -1;

    // --- SHOTS / TURN ---
    private static final int SHOTS_PER_TURN = 1;
    private int shotsRemaining = SHOTS_PER_TURN;

    // --- LEVEL / PROGRESSION ---
    private int playerLevel = 1;
    private static final int FRAGS_PER_LEVEL = 20;

    // --- BUFFS ---
    private int quadTurns = 0;
    private int hasteTurns = 0;

    // --- WEAPONS ---
    private static class Weapon implements Serializable {
        String name;
        int baseDamage;
        double speedModifier; // lower = faster
        Weapon() {}
        Weapon(String name, int baseDamage, double speedModifier) {
            this.name = name; this.baseDamage = baseDamage; this.speedModifier = speedModifier;
        }
    }
    private List<Weapon> weapons = new ArrayList<>();
    private int currentWeapon = 0;

    // --- AMMO: magazynki + rezerwy ---
    private int pistolMag = 12;
    private int pistolReserve = 120;
    private final int PISTOL_MAG_SIZE = 12;
    private final int PISTOL_RESERVE_MAX = 120;

    private int shotgunMag = 6;
    private int shotgunReserve = 24;
    private final int SHOTGUN_MAG_SIZE = 6;
    private final int SHOTGUN_RESERVE_MAX = 24;

    private int railMag = 4;
    private int railReserve = 40;
    private final int RAIL_MAG_SIZE = 4;
    private final int RAIL_RESERVE_MAX = 40;

    // alt ammo (secondary fire) reserves
    private int pistolAltReserve = 12;
    private final int PISTOL_ALT_MAX = 12;
    private int railAltReserve = 8;
    private final int RAIL_ALT_MAX = 8;

    // --- GRENADES ---
    private int grenades = 0; // number of grenades player currently has
    private static final int GRENADES_PER_MISSION = 2;
    private static final char GRENADE_CHAR = 'o';
    private static final Color GRENADE_COLOR = new Color(200, 120, 40);

    // --- ENEMIES ---
    private enum EnemyType { GRUNT, SHOOTER, FAST, TANK, BOSS }
    public static class Enemy implements Serializable {
        public int x, y;
        public int hp;
        public boolean alive = true;
        public EnemyType type;
        public String name;
        public int savedTile = FLOOR;
        public int savedPickupIndex = -1;
        public Enemy() {}
    }
    private List<Enemy> enemies = new ArrayList<>();
    private int fragCount = 0;

    // --- CORPSES ---
    public static class Corpse implements Serializable {
        public int x, y;
        public char ch = 'c';
        public boolean visible = true;
        public Corpse() {}
        public Corpse(int x, int y) { this.x = x; this.y = y; this.ch = 'c'; this.visible = true; }
    }
    private List<Corpse> corpses = new ArrayList<>();

    // --- PICKUPS ---
    private enum PickupType { MEDPACK, QUAD, HASTE, MEGA,
                              BULLETS, BULLETS_HEAVY, SHELLS, RAIL_CELLS, RAIL_HEAVY, TELEPORT, GRENADE }
    public static class Pickup implements Serializable {
        public int x, y;
        public PickupType type;
        public boolean active = true;
        public Pickup() {}
    }
    private List<Pickup> pickups = new ArrayList<>();

    // --- AIM / LOOK ---
    private int aimX, aimY;
    private int lookX, lookY;

    // --- PROJECTILES ---
    private static class Point { int x, y; Point(int x, int y){this.x=x;this.y=y;} }

    private static class TileProjectile {
        List<Point> path;
        int index;
        boolean fromPlayer;
        int damage;
        char ch;
        Color color;
        int msPerTile;
        Timer timer;
        int remainingTurns = -1;
        boolean isGrenade = false;

        TileProjectile(List<Point> path, boolean fromPlayer, int damage, char ch, Color color, int msPerTile, int remainingTurns) {
            this.path = path; this.index = 0; this.fromPlayer = fromPlayer;
            this.damage = damage; this.ch = ch; this.color = color; this.msPerTile = msPerTile;
            this.remainingTurns = remainingTurns;
        }
        Point current() { return path.get(Math.min(index, path.size()-1)); }
    }

    private List<TileProjectile> activeProjectiles = new ArrayList<>();

    // --- EFFECTS ---
    private List<Effect> effects = new ArrayList<>();
    private static class Effect {
        int x, y, life;
        Color color;
        char ch;
        boolean fullTile;
        int alpha;
        Effect(int x,int y,int life,Color color,char ch, boolean fullTile, int alpha){
            this.x=x;this.y=y;this.life=life;this.color=color;this.ch=ch;this.fullTile=fullTile;this.alpha=alpha;
        }
    }

    // --- MESSAGE SYSTEM (5 lines) ---
    private final String[] msgLines = new String[5];
    private String lastMsg = "";

    private Random rng = new Random();

    // --- LOADING state helpers ---
    private Timer loadingBlinkTimer;
    private Timer loadingEndTimer;
    private boolean loadingVisible = true;

    // --- SOUND (optional) ---
    // single-shot sound player (kept for effects)
    private void playSound(String filename) {
        try {
            File file = new File(filename);
            if (!file.exists()) return;
            AudioInputStream in = AudioSystem.getAudioInputStream(file);
            Clip clip = AudioSystem.getClip();
            clip.open(in);
            clip.start();
        } catch (Exception e) { /* ignore */ }
    }
    private void sfxMove() { playSound("sfx1.wav"); }
    private void sfxFire() { playSound("sfx2.wav"); }
    private void sfxDoor() { playSound("sfx3.wav"); }
    private void sfxHit()  { playSound("sfx4.wav"); }
    private void sfxReload() { playSound("sfx5.wav"); }
    private void sfxPickup() { playSound("sfx6.wav"); } // reused for pickup
    private void sfxGrenadeTick() { playSound("sfx7.wav"); }
    // Eksplozja granatu teraz używa sfx7.wav (zmiana zgodnie z prośbą)
    private void sfxGrenadeBoom() { playSound("sfx7.wav"); }

    // --- BACKGROUND LOOP (new) ---
    private Clip bgClip = null;

    /**
     * Startuje odtwarzanie pliku w pętli (LOOP_CONTINUOUSLY).
     * Jeśli plik nie istnieje lub nie można go otworzyć, metoda cicho kończy działanie.
     */
    public synchronized void startBackgroundLoop(String filename) {
        stopBackgroundLoop(); // upewnij się, że nie ma starego klipu
        try {
            File file = new File(filename);
            if (!file.exists()) return;
            AudioInputStream ais = AudioSystem.getAudioInputStream(file);
            Clip clip = AudioSystem.getClip();
            clip.open(ais);
            clip.loop(Clip.LOOP_CONTINUOUSLY);
            // opcjonalnie: nasłuch na zamknięcie aby zwolnić zasoby
            clip.addLineListener(new LineListener() {
                @Override
                public void update(LineEvent event) {
                    if (event.getType() == LineEvent.Type.STOP || event.getType() == LineEvent.Type.CLOSE) {
                        try { clip.close(); } catch (Exception ex) { }
                    }
                }
            });
            bgClip = clip;
        } catch (Exception ex) {
            // ignore: nie przerywamy gry jeśli dźwięk nie zadziała
            bgClip = null;
        }
    }

    /**
     * Zatrzymuje pętlę tła i zwalnia zasoby.
     */
    public synchronized void stopBackgroundLoop() {
        if (bgClip != null) {
            try {
                bgClip.stop();
                bgClip.close();
            } catch (Exception ex) { /* ignore */ }
            bgClip = null;
        }
    }

    // --- SAVE DATA ---
    public static class SaveData implements Serializable {
        public int mission;
        public int px, py, hp, maxHP;
        public int quadTurns, hasteTurns;
        public int[][] map;
        public List<Enemy> enemies;
        public List<Pickup> pickups;
        public List<Corpse> corpses;
        public int fragCount;
        public int playerLevel;
        public int currentWeapon;
        public int playerSavedTile;
        public int playerSavedPickupIndex;
        public int pistolMag, pistolReserve, pistolAltReserve;
        public int shotgunMag, shotgunReserve;
        public int railMag, railReserve, railAltReserve;
        // now saving how many times player progressed (timesCleared)
        public int timesCleared;
        // save grenades
        public int grenades;
        public SaveData() {}
    }

    // --- how many times player progressed through teleports/missions (saved) ---
    private int timesCleared = 0;

    // --- Color constants: brass for '*' and blue for '!' pickups ---
    private static final Color COLOR_BRASS = new Color(181, 166, 66); // mosiądz
    private static final Color COLOR_PICKUP_BLUE = new Color(80, 160, 255); // niebieski dla '!'

    public RoguelikeArenaV11() {
        setPreferredSize(new Dimension(1100, 800));
        setBackground(Color.BLACK);
        setFocusable(true);
        requestFocusInWindow();

        msgLines[0] = "Ready.";
        msgLines[1] = "Press ENTER to start a mission from the title screen.";
        msgLines[2] = "SPACE - aim, ENTER - shoot, L - look, 1/2/3 - weapons";
        msgLines[3] = "";
        msgLines[4] = "";

        weapons.add(new Weapon("Pistol", 6, 1.0));
        weapons.add(new Weapon("Shotgun", 10, 1.4));
        weapons.add(new Weapon("Rail", 14, 0.7));

        try {
            InputStream is = getClass().getResourceAsStream("/font.png");
            if (is != null) {
                fontImage = ImageIO.read(is);
                is.close();
            } else {
                File f = new File("font.png");
                if (f.exists()) fontImage = ImageIO.read(f);
                else fontImage = null;
            }
            if (fontImage != null) {
                GLYPH_COLS = Math.max(1, fontImage.getWidth() / GLYPH_W);
            }
        } catch (IOException ex) {
            ex.printStackTrace();
            fontImage = null;
            GLYPH_COLS = 0;
        }

        initGlyphOverrides();

        addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_F5) { saveGame(); addMsg("Game saved."); repaint(); return; }
                if (e.getKeyCode() == KeyEvent.VK_F9) { if (loadGame()) addMsg("Save loaded."); else addMsg("No save found."); repaint(); return; }

                if (e.getKeyCode() == KeyEvent.VK_1) { currentWeapon=0; addMsg("Weapon: "+weapons.get(currentWeapon).name); repaint(); return; }
                if (e.getKeyCode() == KeyEvent.VK_2) { currentWeapon=1; addMsg("Weapon: "+weapons.get(currentWeapon).name); repaint(); return; }
                if (e.getKeyCode() == KeyEvent.VK_3) { currentWeapon=2; addMsg("Weapon: "+weapons.get(currentWeapon).name); repaint(); return; }

                if (e.getKeyCode() == KeyEvent.VK_O) {
                    if (state == GameState.AIMING) { openDoorAt(aimX, aimY); }
                    else { if (!tryOpenDoor()) addMsg("No closed door nearby."); }
                    repaint(); return;
                }
                if (e.getKeyCode() == KeyEvent.VK_C) {
                    if (state == GameState.AIMING) { closeDoorAt(aimX, aimY); }
                    else { if (!tryCloseDoor()) addMsg("No open door nearby."); }
                    repaint(); return;
                }
                if (e.getKeyCode() == KeyEvent.VK_T) {
                    if (state == GameState.AIMING) { toggleDoorAt(aimX, aimY); repaint(); return; }
                    else { addMsg("T works only in aim mode."); repaint(); return; }
                }

                if (e.getKeyCode() == KeyEvent.VK_L) {
                    if (state == GameState.LOOK) {
                        state = GameState.PLAYING;
                        addMsg("Look canceled.");
                    } else {
                        enterLookMode();
                    }
                    repaint();
                    return;
                }

                switch (state) {
                    case TITLE -> handleTitleInput(e);
                    case MENU -> handleMenuInput(e);
                    case PLAYING -> handleGameInput(e);
                    case AIMING -> handleAimInput(e);
                    case LOOK -> handleLookInput(e);
                    case ANIMATING -> { }
                    case LOADING -> { }
                    case GAME_OVER -> handleGameOverInput(e);
                }
                repaint();
            }
        });
    }

    private void initGlyphOverrides() {
        glyphOverrides.clear();
        int idxAt = GLYPH_MAP.indexOf('@');
        if (idxAt >= 0) glyphOverrides.put('@', idxAt);
        int idxG = GLYPH_MAP.indexOf('g'); if (idxG >= 0) glyphOverrides.put('g', idxG);
        int idxS = GLYPH_MAP.indexOf('s'); if (idxS >= 0) glyphOverrides.put('s', idxS);
        int idxF = GLYPH_MAP.indexOf('f'); if (idxF >= 0) glyphOverrides.put('f', idxF);
        int idxT = GLYPH_MAP.indexOf('T'); if (idxT >= 0) glyphOverrides.put('T', idxT);
        int idxK = GLYPH_MAP.indexOf('K'); if (idxK >= 0) glyphOverrides.put('K', idxK);
        int idxC = GLYPH_MAP.indexOf('C'); if (idxC >= 0) glyphOverrides.put('C', idxC);
        int idxBang = GLYPH_MAP.indexOf('!'); if (idxBang >= 0) glyphOverrides.put('!', idxBang);
        int idxc = GLYPH_MAP.indexOf('c'); if (idxc >= 0) glyphOverrides.put('c', idxc);
        int idxo = GLYPH_MAP.indexOf('o'); if (idxo >= 0) glyphOverrides.put('o', idxo);
    }

    private void setPixelRenderingHints(Graphics2D g2) {
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
    }

    private int charToGlyphIndex(char ch) {
        Integer ov = glyphOverrides.get(ch);
        if (ov != null) return ov;

        int idx = GLYPH_MAP.indexOf(ch);
        if (idx >= 0) return idx;
        switch (ch) {
            case 'ą': return GLYPH_MAP.indexOf('a');
            case 'ć': return GLYPH_MAP.indexOf('c');
            case 'ę': return GLYPH_MAP.indexOf('e');
            case 'ł': return GLYPH_MAP.indexOf('l');
            case 'ń': return GLYPH_MAP.indexOf('n');
            case 'ó': return GLYPH_MAP.indexOf('o');
            case 'ś': return GLYPH_MAP.indexOf('s');
            case 'ż': case 'ź': return GLYPH_MAP.indexOf('z');
            case '\n': return 0;
            default:
                int q = GLYPH_MAP.indexOf('?');
                return q >= 0 ? q : 0;
        }
    }

    private void drawCharRL(Graphics2D g2, char ch, int sx, int sy, int tileSize, Color color) {
        if (fontImage == null || GLYPH_COLS <= 0) {
            g2.setColor(color);
            g2.drawString(String.valueOf(ch), sx + tileSize/4, sy + tileSize - 6);
            return;
        }
        int index = charToGlyphIndex(ch);
        int glyphsPerRow = GLYPH_COLS;
        int cx = (index % glyphsPerRow) * GLYPH_W;
        int cy = (index / glyphsPerRow) * GLYPH_H;

        double scale = Math.min((double)tileSize / GLYPH_W, (double)tileSize / GLYPH_H);
        int destW = Math.max(1, (int)Math.round(GLYPH_W * scale));
        int destH = Math.max(1, (int)Math.round(GLYPH_H * scale));
        int dx = sx + (tileSize - destW) / 2;
        int dy = sy + (tileSize - destH) / 2;

        BufferedImage glyph = new BufferedImage(destW, destH, BufferedImage.TYPE_INT_ARGB);
        Graphics2D gg = glyph.createGraphics();
        setPixelRenderingHints(gg);
        gg.drawImage(fontImage, 0, 0, destW, destH, cx, cy, cx + GLYPH_W, cy + GLYPH_H, null);
        gg.dispose();

        int rgbColor = (color.getRed() << 16) | (color.getGreen() << 8) | color.getBlue();
        for (int y = 0; y < destH; y++) {
            for (int x = 0; x < destW; x++) {
                int px = glyph.getRGB(x, y);
                int a = (px >> 24) & 0xff;
                if (a == 0) continue;
                int newPx = (a << 24) | rgbColor;
                glyph.setRGB(x, y, newPx);
            }
        }

        Composite oldComp = g2.getComposite();
        g2.drawImage(glyph, dx, dy, null);
        g2.setComposite(oldComp);
    }

    private void drawStringRL(Graphics2D g2, String text, int sx, int sy, int tileSize, Color color) {
        if (text == null) return;
        int x = sx;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            drawCharRL(g2, ch, x, sy, tileSize, color);
            x += tileSize;
        }
    }

    private void drawCenteredRL(Graphics2D g2, String text, int cx, int cy, int tileSize, Color color) {
        if (text == null) return;
        int w = text.length() * tileSize;
        int sx = cx - w/2;
        int sy = cy - tileSize/2;
        drawStringRL(g2, text, sx, sy, tileSize, color);
    }

    private synchronized void addMsg(String text) {
        if (text == null) text = "";
        lastMsg = text;
        for (int i = msgLines.length - 1; i > 0; i--) msgLines[i] = msgLines[i-1];
        msgLines[0] = text;
    }

    private void enterLookMode() {
        lookX = px;
        lookY = py;
        state = GameState.LOOK;
        addMsg("Look mode: use arrows to move, ESC or L to exit.");
    }

    private void handleLookInput(KeyEvent e) {
        if (e.getKeyCode() == KeyEvent.VK_ESCAPE) { state = GameState.PLAYING; addMsg("Look canceled."); return; }
        int dx=0, dy=0;
        switch (e.getKeyCode()) {
            case KeyEvent.VK_LEFT, KeyEvent.VK_NUMPAD4 -> dx=-1;
            case KeyEvent.VK_RIGHT, KeyEvent.VK_NUMPAD6 -> dx=1;
            case KeyEvent.VK_UP, KeyEvent.VK_NUMPAD8 -> dy=-1;
            case KeyEvent.VK_DOWN, KeyEvent.VK_NUMPAD2 -> dy=1;
            case KeyEvent.VK_NUMPAD7 -> { dx=-1; dy=-1; }
            case KeyEvent.VK_NUMPAD9 -> { dx=1; dy=-1; }
            case KeyEvent.VK_NUMPAD1 -> { dx=-1; dy=1; }
            case KeyEvent.VK_NUMPAD3 -> { dx=1; dy=1; }
            default -> {}
        }
        if (dx!=0 || dy!=0) {
            int nx = lookX + dx, ny = lookY + dy;
            if (nx>=0 && ny>=0 && nx<MAP_W && ny<MAP_H) {
                lookX = nx; lookY = ny;
                lookAt(lookX, lookY);
            }
        }
    }

    private void lookAt(int x, int y) {
        if (x<0||y<0||x>=MAP_W||y>=MAP_H) { addMsg("Out of bounds."); return; }
        StringBuilder sb = new StringBuilder();
        int t = map[y][x];
        switch (t) {
            case FLOOR -> sb.append("Floor");
            case WALL -> sb.append("Wall");
            case DOOR_CLOSED -> sb.append("Closed door");
            case DOOR_OPEN -> sb.append("Open door");
            default -> sb.append("Unknown terrain");
        }
        Enemy e = getEnemyAt(x,y);
        if (e != null && e.alive) {
            sb.append(" | Enemy: ").append(e.name != null ? e.name : e.type.name()).append(" HP:").append(e.hp);
        } else {
            Pickup p = getPickupAt(x,y);
            if (p != null && p.active) {
                sb.append(" | Pickup: ").append(p.type.name());
            } else {
                Corpse c = getCorpseAt(x,y);
                if (c != null) sb.append(" | Corpse: c");
            }
        }
        int d = distCheb(px,py,x,y);
        sb.append(" | Distance: ").append(d);
        addMsg(sb.toString());
    }

    private void handleTitleInput(KeyEvent e) {
        if (e.getKeyCode() == KeyEvent.VK_ENTER) {
            state = GameState.MENU;
            addMsg("Menu opened. Press ENTER to start mission.");
        }
        if (e.getKeyCode() == KeyEvent.VK_ESCAPE) {
            System.exit(0);
        }
    }

    private void handleMenuInput(KeyEvent e) {
        if (e.getKeyCode() == KeyEvent.VK_ENTER) { mission=1; startMission(); }
        if (e.getKeyCode() == KeyEvent.VK_ESCAPE) { state = GameState.TITLE; addMsg("Back to title."); }
    }

    private void handleGameInput(KeyEvent e) {
        if (e.getKeyCode() == KeyEvent.VK_ESCAPE) { state=GameState.MENU; addMsg("Back to menu."); return; }
        if (e.getKeyCode() == KeyEvent.VK_SPACE) { enterAimMode(); return; }

        if (e.getKeyCode() == KeyEvent.VK_W) {
            waitTurn();
            return;
        }

        if (e.getKeyCode() == KeyEvent.VK_R) {
            reloadWeapon();
            return;
        }

        int dx=0, dy=0;
        switch (e.getKeyCode()) {
            case KeyEvent.VK_LEFT, KeyEvent.VK_NUMPAD4 -> dx=-1;
            case KeyEvent.VK_RIGHT, KeyEvent.VK_NUMPAD6 -> dx=1;
            case KeyEvent.VK_UP, KeyEvent.VK_NUMPAD8 -> dy=-1;
            case KeyEvent.VK_DOWN, KeyEvent.VK_NUMPAD2 -> dy=1;
            case KeyEvent.VK_NUMPAD7 -> { dx=-1; dy=-1; }
            case KeyEvent.VK_NUMPAD9 -> { dx=1; dy=-1; }
            case KeyEvent.VK_NUMPAD1 -> { dx=-1; dy=1; }
            case KeyEvent.VK_NUMPAD3 -> { dx=1; dy=1; }
            default -> { return; }
        }
        movePlayer(dx, dy);
    }

    private void handleAimInput(KeyEvent e) {
        if (e.getKeyCode() == KeyEvent.VK_ESCAPE) { state=GameState.PLAYING; addMsg("Aim canceled."); return; }
        int dx=0, dy=0;
        switch (e.getKeyCode()) {
            case KeyEvent.VK_LEFT, KeyEvent.VK_NUMPAD4 -> dx=-1;
            case KeyEvent.VK_RIGHT, KeyEvent.VK_NUMPAD6 -> dx=1;
            case KeyEvent.VK_UP, KeyEvent.VK_NUMPAD8 -> dy=-1;
            case KeyEvent.VK_DOWN, KeyEvent.VK_NUMPAD2 -> dy=1;
            case KeyEvent.VK_NUMPAD7 -> { dx=-1; dy=-1; }
            case KeyEvent.VK_NUMPAD9 -> { dx=1; dy=-1; }
            case KeyEvent.VK_NUMPAD1 -> { dx=-1; dy=1; }
            case KeyEvent.VK_NUMPAD3 -> { dx=1; dy=1; }
            default -> {}
        }
        if (dx!=0 || dy!=0) {
            int nx=aimX+dx, ny=aimY+dy;
            if (nx>=0 && ny>=0 && nx<MAP_W && ny<MAP_H) { aimX=nx; aimY=ny; }
            return;
        }
        if (e.getKeyCode() == KeyEvent.VK_ENTER) {
            if (shotsRemaining <= 0) {
                addMsg("No shots left this turn.");
                state = GameState.PLAYING;
                return;
            }
            confirmShot();
        }
        // Throw grenade when in aim mode and press G
        if (e.getKeyCode() == KeyEvent.VK_G) {
            if (grenades <= 0) {
                addMsg("No grenades to throw.");
                state = GameState.PLAYING;
                return;
            }
            if (shotsRemaining <= 0) {
                addMsg("No actions left this turn to throw a grenade.");
                state = GameState.PLAYING;
                return;
            }
            confirmGrenade();
        }
    }

    private void handleGameOverInput(KeyEvent e) {
        if (e.getKeyCode() == KeyEvent.VK_ENTER) {
            resetAllStats();
            addMsg("All stats reset. Back to menu.");
            state = GameState.MENU;
        }
        if (e.getKeyCode() == KeyEvent.VK_ESCAPE) System.exit(0);
    }

    private void resetAllStats() {
        fragCount = 0;
        playerLevel = 1;
        weapons.clear();
        weapons.add(new Weapon("Pistol", 6, 1.0));
        weapons.add(new Weapon("Shotgun", 10, 1.4));
        weapons.add(new Weapon("Rail", 14, 0.7));
        currentWeapon = 0;
        maxHP = 100;
        hp = maxHP;
        quadTurns = 0;
        hasteTurns = 0;
        mission = 1;
        shotsRemaining = SHOTS_PER_TURN;
        enemies.clear();
        pickups.clear();
        corpses.clear();
        effects.clear();
        activeProjectiles.forEach(tp -> { if (tp.timer!=null) tp.timer.stop(); });
        activeProjectiles.clear();
        if (loadingBlinkTimer != null) { loadingBlinkTimer.stop(); loadingBlinkTimer = null; }
        if (loadingEndTimer != null) { loadingEndTimer.stop(); loadingEndTimer = null; }

        pistolMag = PISTOL_MAG_SIZE; pistolReserve = PISTOL_RESERVE_MAX; pistolAltReserve = PISTOL_ALT_MAX;
        shotgunMag = SHOTGUN_MAG_SIZE; shotgunReserve = SHOTGUN_RESERVE_MAX;
        railMag = RAIL_MAG_SIZE; railReserve = RAIL_RESERVE_MAX; railAltReserve = RAIL_ALT_MAX;

        timesCleared = 0;
        grenades = 0;
    }

    private void startMission() {
        if (loadingBlinkTimer != null) { loadingBlinkTimer.stop(); loadingBlinkTimer = null; }
        if (loadingEndTimer != null) { loadingEndTimer.stop(); loadingEndTimer = null; }
        loadingVisible = true;

        generateMap();
        placePlayer();
        spawnEnemies(ENEMIES_PER_MISSION);
        spawnPickups();
        corpses.clear();
        hp = Math.min(hp, maxHP);
        quadTurns = 0; hasteTurns = 0;
        shotsRemaining = SHOTS_PER_TURN;
        // give player grenades for this mission
        grenades = GRENADES_PER_MISSION;
        addMsg("Mission " + mission + " — Fight! (timesCleared: " + timesCleared + ")  Grenades: " + grenades);
        state = GameState.PLAYING;
    }

    private void generateMap() {
        for (int y=0;y<MAP_H;y++) for (int x=0;x<MAP_W;x++) {
            if (x==0||y==0||x==MAP_W-1||y==MAP_H-1) map[y][x]=WALL;
            else {
                double r=rng.nextDouble();
                if (r<0.12) map[y][x]=WALL;
                else if (r<0.14) map[y][x]=DOOR_CLOSED;
                else map[y][x]=FLOOR;
            }
        }
    }

    private void placePlayer() {
        px = MAP_W/2; py = MAP_H/2;
        if (map[py][px]==WALL) map[py][px]=FLOOR;
        playerSavedTile = map[py][px];
        playerSavedPickupIndex = indexOfPickupAt(px, py);
        map[py][px] = FLOOR;
        if (playerSavedPickupIndex >= 0) pickups.get(playerSavedPickupIndex).active = false;
    }

    private void spawnEnemies(int count) {
        enemies.clear();
        int tries=0;
        while (enemies.size()<count && tries<10000) {
            int x=rng.nextInt(MAP_W), y=rng.nextInt(MAP_H); tries++;
            if (!isWalkable(x,y)) continue;
            if (x==px && y==py) continue;
            if (getEnemyAt(x,y)!=null) continue;
            Enemy e = new Enemy();
            e.x=x; e.y=y;
            double t = rng.nextDouble();
            if (t < 0.45) e.type = EnemyType.GRUNT;
            else if (t < 0.7) e.type = EnemyType.SHOOTER;
            else if (t < 0.9) e.type = EnemyType.FAST;
            else e.type = EnemyType.TANK;
            // base HP depending on type and mission
            switch (e.type) {
                case GRUNT -> e.hp = 1 + Math.max(0, mission/2);
                case SHOOTER -> e.hp = 1 + Math.max(0, mission/2);
                case FAST -> e.hp = 1 + Math.max(0, mission/2);
                case TANK -> e.hp = 4 + mission;
                default -> e.hp = 1 + Math.max(0, mission/2);
            }
            // scale HP by timesCleared to make it harder each time player progressed
            if (e.type != EnemyType.BOSS) {
                // increase by 2 HP per cleared progression as a reasonable scaling factor
                e.hp += timesCleared * 2;
                // cap ordinary enemies' HP to 100 (bosses unaffected)
                e.hp = Math.min(e.hp, 100);
            }
            e.savedTile = map[y][x];
            e.savedPickupIndex = indexOfPickupAt(x, y);
            map[y][x] = FLOOR;
            if (e.savedPickupIndex >= 0) pickups.get(e.savedPickupIndex).active = false;

            enemies.add(e);
        }

        // If this is the final mission, ensure a single strong boss KONFI is present.
        if (mission >= MAX_MISSION) {
            // Try to place boss in a safe random walkable tile not on player and not on other enemies
            int btries = 0;
            boolean placed = false;
            while (!placed && btries < 5000) {
                btries++;
                int bx = rng.nextInt(MAP_W), by = rng.nextInt(MAP_H);
                if (!isWalkable(bx, by)) continue;
                if (bx == px && by == py) continue;
                if (getEnemyAt(bx, by) != null) continue;
                // create boss
                Enemy boss = new Enemy();
                boss.x = bx; boss.y = by;
                boss.type = EnemyType.BOSS;
                boss.name = "KONFI";
                // Boss HP: base high value plus scaling by timesCleared to keep challenge across runs
                boss.hp = 200 + timesCleared * 10;
                // Do NOT cap boss HP to 100 (bosses unaffected by ordinary cap)
                boss.savedTile = map[by][bx];
                boss.savedPickupIndex = indexOfPickupAt(bx, by);
                map[by][bx] = FLOOR;
                if (boss.savedPickupIndex >= 0) pickups.get(boss.savedPickupIndex).active = false;
                enemies.add(boss);
                placed = true;
            }
            if (!placed) {
                // fallback: place boss near player if random placement failed
                int bx = Math.max(1, Math.min(MAP_W-2, px + 3));
                int by = Math.max(1, Math.min(MAP_H-2, py + 3));
                if (getEnemyAt(bx, by) == null && isWalkable(bx, by)) {
                    Enemy boss = new Enemy();
                    boss.x = bx; boss.y = by;
                    boss.type = EnemyType.BOSS;
                    boss.name = "KONFI";
                    boss.hp = 200 + timesCleared * 10;
                    boss.savedTile = map[by][bx];
                    boss.savedPickupIndex = indexOfPickupAt(bx, by);
                    map[by][bx] = FLOOR;
                    if (boss.savedPickupIndex >= 0) pickups.get(boss.savedPickupIndex).active = false;
                    enemies.add(boss);
                }
            }
            addMsg("A powerful presence can be felt... KONFI awaits on this mission.");
        }
    }

    private void spawnPickups() {
        pickups.clear();
        for (int i=0;i<8;i++) addPickupRandom(PickupType.MEDPACK);
        for (int i=0;i<3;i++) addPickupRandom(PickupType.QUAD);
        for (int i=0;i<3;i++) addPickupRandom(PickupType.HASTE);
        for (int i=0;i<1;i++) addPickupRandom(PickupType.MEGA);
        for (int i=0;i<3;i++) addPickupRandom(PickupType.BULLETS);
        for (int i=0;i<2;i++) addPickupRandom(PickupType.SHELLS);
        for (int i=0;i<2;i++) addPickupRandom(PickupType.RAIL_CELLS);
        // grenade pickups are not spawned by default; grenades are given as inventory.
    }

    private void addPickupRandom(PickupType type) {
        int tries=0;
        while (tries<2000) {
            int x=rng.nextInt(MAP_W), y=rng.nextInt(MAP_H); tries++;
            if (!isWalkable(x,y)) continue;
            if (x==px && y==py) continue;
            if (getEnemyAt(x,y)!=null) continue;
            if (getPickupAt(x,y)!=null) continue;
            // avoid placing initial pickups on corpses
            if (getCorpseAt(x,y)!=null) continue;
            Pickup p=new Pickup(); p.x=x; p.y=y; p.type=type; pickups.add(p); return;
        }
    }

    private void addPickupAt(int x, int y, PickupType type) {
        if (x<0||y<0||x>=MAP_W||y>=MAP_H) return;
        if (getPickupAt(x,y)!=null) return;
        Pickup p = new Pickup(); p.x = x; p.y = y; p.type = type; p.active = true;
        pickups.add(p);
    }

    private boolean isWalkable(int x,int y) {
        if (x<0||y<0||x>=MAP_W||y>=MAP_H) return false;
        int t = map[y][x];
        return t==FLOOR || t==DOOR_OPEN;
    }

    private Enemy getEnemyAt(int x,int y) {
        for (Enemy e: enemies) if (e.alive && e.x==x && e.y==y) return e;
        return null;
    }

    private Pickup getPickupAt(int x,int y) {
        for (Pickup p: pickups) if (p.active && p.x==x && p.y==y) return p;
        return null;
    }

    private int indexOfPickupAt(int x,int y) {
        for (int i=0;i<pickups.size();i++) {
            Pickup p = pickups.get(i);
            if (p != null && p.active && p.x==x && p.y==y) return i;
        }

        return -1;
    }

    private Corpse getCorpseAt(int x,int y) {
        for (Corpse c : corpses) if (c != null && c.visible && c.x==x && c.y==y) return c;
        return null;
    }

    private int indexOfCorpseAt(int x,int y) {
        for (int i=0;i<corpses.size();i++) {
            Corpse c = corpses.get(i);
            if (c != null && c.visible && c.x==x && c.y==y) return i;
        }
        return -1;
    }

    private boolean allEnemiesDead() {
        for (Enemy e: enemies) if (e.alive) return false;
        return true;
    }

    private int distCheb(int x1,int y1,int x2,int y2) { return Math.max(Math.abs(x1-x2), Math.abs(y1-y2)); }

    private void movePlayer(int dx,int dy) {
        if (state == GameState.GAME_OVER) return;
        int nx=px+dx, ny=py+dy;
        if (nx<0||ny<0||nx>=MAP_W||ny>=MAP_H) return;
        int t = map[ny][nx];
        if (t==WALL || t==DOOR_CLOSED) return;
        if (getEnemyAt(nx,ny)!=null) return;

        restorePlayerUnderlying();

        px=nx; py=ny; sfxMove();

        Pickup p = getPickupAt(px, py);
        if (p != null && p.active) {
            applyPickup(p);
            p.active = false;
        }

        advanceProjectilesAfterPlayerMove();

        enemyTurn();

        if (allEnemiesDead()) {
            if (!hasActiveTeleport()) {
                spawnTeleportRandom();
                addMsg("A blue teleport appears. Enter it to proceed.");
            }
        }
    }

    private void restorePlayerUnderlying() {
        // placeholder for restoring saved tile/pickup under player if needed
    }

    private void advanceProjectilesAfterPlayerMove() {
        Iterator<TileProjectile> it = activeProjectiles.iterator();
        boolean changed = false;
        while (it.hasNext()) {
            TileProjectile tp = it.next();
            if (!tp.fromPlayer) continue;
            if (tp.remainingTurns >= 0) {
                tp.remainingTurns--;
                if (tp.remainingTurns <= 0) {
                    if (tp.timer != null) {
                        try { tp.timer.stop(); } catch (Exception ex) { }
                    }
                    it.remove();
                    changed = true;
                }
            }
        }
        if (changed) repaint();
    }

    private void applyPickup(Pickup p) {
        if (p == null) return;
        sfxPickup();
        switch (p.type) {
            case MEDPACK -> {
                int heal = 20;
                hp = Math.min(maxHP, hp + heal);
                addMsg("Medpack: +" + heal + " HP (" + hp + "/" + maxHP + ")");
                effects.add(new Effect(p.x, p.y, 30, new Color(200,0,0), '+', false, 200));
            }
            case QUAD -> {
                quadTurns += 50;
                addMsg("Quad damage picked up!");
                effects.add(new Effect(p.x, p.y, 40, new Color(255,200,0), 'Q', false, 200));
            }
            case HASTE -> {
                hasteTurns += 50;
                addMsg("Haste picked up!");
                effects.add(new Effect(p.x, p.y, 40, new Color(0,200,255), 'H', false, 200));
            }
            case MEGA -> {
                int bonus = 40;
                maxHP += bonus;
                maxHP = Math.min(maxHP, 192);
                hp = Math.min(maxHP, hp + bonus);
                addMsg("MegaHealth! MaxHP +" + bonus + " (" + hp + "/" + maxHP + ")");
                effects.add(new Effect(p.x, p.y, 30, new Color(0,160,0), 'M', false, 200));
            }
            case BULLETS -> {
                pistolReserve = Math.min(PISTOL_RESERVE_MAX, pistolReserve + 30);
                addMsg("Picked up bullets. Pistol reserve: " + pistolReserve);
                effects.add(new Effect(p.x, p.y, 20, COLOR_PICKUP_BLUE, '!', false, 200));
            }
            case BULLETS_HEAVY -> {
                pistolAltReserve = Math.min(PISTOL_ALT_MAX, pistolAltReserve + 6);
                addMsg("Picked up heavy bullets. Alt ammo: " + pistolAltReserve);
                effects.add(new Effect(p.x, p.y, 20, COLOR_PICKUP_BLUE, '!', false, 200));
            }
            case SHELLS -> {
                shotgunReserve = Math.min(SHOTGUN_RESERVE_MAX, shotgunReserve + 8);
                addMsg("Picked up shells. Shell reserve: " + shotgunReserve);
                effects.add(new Effect(p.x, p.y, 20, COLOR_PICKUP_BLUE, '!', false, 200));
            }
            case RAIL_CELLS -> {
                railReserve = Math.min(RAIL_RESERVE_MAX, railReserve + 10);
                addMsg("Picked up rail cells. Rail reserve: " + railReserve);
                effects.add(new Effect(p.x, p.y, 20, COLOR_PICKUP_BLUE, '!', false, 200));
            }
            case RAIL_HEAVY -> {
                railAltReserve = Math.min(RAIL_ALT_MAX, railAltReserve + 4);
                addMsg("Picked up heavy rail cells. Alt rail ammo: " + railAltReserve);
                effects.add(new Effect(p.x, p.y, 20, COLOR_PICKUP_BLUE, '!', false, 200));
            }
            case TELEPORT -> {
                // increment timesCleared each time player uses teleport to proceed
                timesCleared++;
                if (mission >= MAX_MISSION) {
                    addMsg("You cleared all missions! Victory! (timesCleared: " + timesCleared + ")");
                    state = GameState.GAME_OVER;
                } else {
                    mission++;
                    addMsg("Teleport used. Proceeding to mission " + mission + ". (timesCleared: " + timesCleared + ")");
                    startMission();
                }
            }
            case GRENADE -> {
                grenades++;
                addMsg("Picked up a grenade! Grenades: " + grenades);
                effects.add(new Effect(p.x, p.y, 30, GRENADE_COLOR, 'G', false, 200));
            }

        }

    }

    private void enterAimMode() {
        state = GameState.AIMING;
        aimX = px; aimY = py;
        addMsg("Aim mode: move cursor and press ENTER to fire. Press G to throw grenade.");
    }

    private void spawnProjectileFromPlayer(List<Point> path, int damage, char ch, Color color, int msPerTile) {
        int remaining = 3;
        // ensure projectile color for '*' is brass by default
        Color projColor = (ch == '*') ? COLOR_BRASS : color;
        TileProjectile tp = new TileProjectile(path, true, damage, ch, projColor, msPerTile, remaining);
        activeProjectiles.add(tp);
        sfxFire();
        animateProjectile(tp);
    }

    private void confirmShot() {
        boolean canShoot = false;
        switch (currentWeapon) {
            case 0 -> canShoot = pistolMag > 0;
            case 1 -> canShoot = shotgunMag > 0;
            case 2 -> canShoot = railMag > 0;
        }
        if (!canShoot) {
            addMsg("No ammo in magazine. Reload.");
            state = GameState.PLAYING;
            return;
        }

        List<Point> path = computeLine(px, py, aimX, aimY);
        Weapon w = weapons.get(currentWeapon);
        int baseDamage = w.baseDamage;
        int damage = baseDamage;
        if (quadTurns > 0) damage *= 4;

        switch (currentWeapon) {
            case 0 -> pistolMag--;
            case 1 -> shotgunMag--;
            case 2 -> railMag--;
        }

        // spawn projectile with '*' char; color will be set to brass in spawnProjectileFromPlayer
        spawnProjectileFromPlayer(path, damage, '*', COLOR_BRASS, 30);

        shotsRemaining--;
        state = GameState.PLAYING;
    }

    private void confirmGrenade() {
        // ensure grenade action consumes one shot for the turn so player cannot throw two grenades in one turn
        if (shotsRemaining <= 0) {
            addMsg("No actions left this turn to throw a grenade.");
            state = GameState.PLAYING;
            return;
        }
        // consume grenade and animate throw
        grenades--;
        shotsRemaining--; // consume the action for this turn
        addMsg("You throw a grenade. Grenades left: " + grenades);
        List<Point> path = computeLine(px, py, aimX, aimY);
        // limit grenade travel distance to e.g. 8 tiles
        int maxRange = 8;
        if (path.size() > maxRange+1) path = path.subList(0, maxRange+1);
        // create projectile and mark as grenade
        TileProjectile tp = new TileProjectile(new ArrayList<>(path), true, 0, GRENADE_CHAR, GRENADE_COLOR, 80, -1);
        tp.isGrenade = true;
        activeProjectiles.add(tp);
        animateGrenade(tp);
        state = GameState.PLAYING;
    }

    private List<Point> computeLine(int x0, int y0, int x1, int y1) {
        List<Point> pts = new ArrayList<>();
        int dx = Math.abs(x1 - x0), sx = x0 < x1 ? 1 : -1;
        int dy = -Math.abs(y1 - y0), sy = y0 < y1 ? 1 : -1;
        int err = dx + dy, e2;
        int x = x0, y = y0;
        while (true) {
            pts.add(new Point(x, y));
            if (x == x1 && y == y1) break;
            e2 = 2 * err;
            if (e2 >= dy) { err += dy; x += sx; }
            if (e2 <= dx) { err += dx; y += sy; }
        }
        return pts;
    }

    private void animateProjectile(TileProjectile tp) {
        if (tp == null || tp.path == null) return;
        // advance along path until hit or end
        for (int i = 1; i < tp.path.size(); i++) {
            Point pt = tp.path.get(i);
            Enemy e = getEnemyAt(pt.x, pt.y);
            if (e != null && e.alive) {
                e.hp -= tp.damage;
                effects.add(new Effect(pt.x, pt.y, 10, Color.RED, 'x', false, 200));
                sfxHit();
                if (e.hp <= 0) {
                    e.alive = false;
                    fragCount++;
                    addMsg("Enemy down! Frags: " + fragCount);
                    // create corpse at enemy location
                    Corpse corpse = new Corpse(e.x, e.y);
                    corpses.add(corpse);
                    // roll for drop; if drop occurs, place pickup on corpse (including grenade drop chance)
                    if (rng.nextDouble() < 0.25) addPickupAt(e.x, e.y, PickupType.BULLETS);
                    if (rng.nextDouble() < 0.05) addPickupAt(e.x, e.y, PickupType.GRENADE);
                    checkLevelUp();
                }
                return;
            }
            if (!isWalkable(pt.x, pt.y)) {
                effects.add(new Effect(pt.x, pt.y, 6, Color.GRAY, '#', true, 120));
                return;
            }
        }
    }

    private void checkLevelUp() {
        int needed = FRAGS_PER_LEVEL * playerLevel;
        if (fragCount >= needed) {
            playerLevel++;
            maxHP += 20;
            maxHP = Math.min(maxHP, 192);
            hp = Math.min(maxHP, hp + 20);
            addMsg("Level up! Level: " + playerLevel + " HP: " + hp + "/" + maxHP);
        }
    }

    private void enemyTurn() {
        for (Enemy e : enemies) {
            if (!e.alive) continue;
            int dx = Integer.compare(px, e.x);
            int dy = Integer.compare(py, e.y);
            int nx = e.x + dx, ny = e.y + dy;
            if (isWalkable(nx, ny) && getEnemyAt(nx, ny) == null && !(nx==px && ny==py)) {
                e.x = nx; e.y = ny;
            } else {
                int rx = e.x + (rng.nextInt(3)-1);
                int ry = e.y + (rng.nextInt(3)-1);
                if (isWalkable(rx, ry) && getEnemyAt(rx, ry) == null && !(rx==px && ry==py)) {
                    e.x = rx; e.y = ry;
                }
            }
            if (distCheb(e.x, e.y, px, py) <= 1) {
                int dmg = switch (e.type) {
                    case GRUNT -> 4;
                    case SHOOTER -> 3;
                    case FAST -> 2;
                    case TANK -> 8;
                    case BOSS -> 20; // increased boss melee damage for final mission
                    default -> 3;
                };
                dmg = Math.max(1, dmg - 1);
                hp -= dmg;
                sfxHit();
                addMsg("Hit for " + dmg + " HP (" + hp + "/" + maxHP + ")");
                if (hp <= 0) {
                    hp = 0;
                    addMsg("You died.");
                    state = GameState.GAME_OVER;
                    return;
                }
            }
        }
        if (quadTurns > 0) quadTurns--;
        if (hasteTurns > 0) hasteTurns--;
        shotsRemaining = SHOTS_PER_TURN;
    }

    private boolean tryOpenDoor() {
        for (int dy=-1; dy<=1; dy++) for (int dx=-1; dx<=1; dx++) {
            int nx = px + dx, ny = py + dy;
            if (nx<0||ny<0||nx>=MAP_W||ny>=MAP_H) continue;
            if (map[ny][nx] == DOOR_CLOSED) {
                map[ny][nx] = DOOR_OPEN;
                sfxDoor();
                addMsg("You opened a door.");
                return true;
            }
        }
        return false;
    }

    private boolean tryCloseDoor() {
        for (int dy=-1; dy<=1; dy++) for (int dx=-1; dx<=1; dx++) {
            int nx = px + dx, ny = py + dy;
            if (nx<0||ny<0||nx>=MAP_W||ny>=MAP_H) continue;
            if (map[ny][nx] == DOOR_OPEN) {
                map[ny][nx] = DOOR_CLOSED;
                sfxDoor();
                addMsg("You closed a door.");
                return true;
            }
        }
        return false;
    }

    private void openDoorAt(int x, int y) {
        if (x<0||y<0||x>=MAP_W||y>=MAP_H) return;
        if (map[y][x] == DOOR_CLOSED) {
            map[y][x] = DOOR_OPEN;
            sfxDoor();
            addMsg("Door opened.");
        } else addMsg("No closed door there.");
    }

    private void closeDoorAt(int x, int y) {
        if (x<0||y<0||x>=MAP_W||y>=MAP_H) return;
        if (map[y][x] == DOOR_OPEN) {
            map[y][x] = DOOR_CLOSED;
            sfxDoor();
            addMsg("Door closed.");
        } else addMsg("No open door there.");
    }

    private void toggleDoorAt(int x, int y) {
        if (x<0||y<0||x>=MAP_W||y>=MAP_H) return;
        if (map[y][x] == DOOR_OPEN) closeDoorAt(x,y);
        else if (map[y][x] == DOOR_CLOSED) openDoorAt(x,y);
        else addMsg("No door there.");
    }

    private boolean hasActiveTeleport() {
        for (Pickup p : pickups) {
            if (p != null && p.active && p.type == PickupType.TELEPORT) return true;
        }

        return false;
    }

    private void spawnTeleportRandom() {
        int tries = 0;
        while (tries < 5000) {
            tries++;
            int x = rng.nextInt(MAP_W), y = rng.nextInt(MAP_H);
            if (!isWalkable(x,y)) continue;
            if (x==px && y==py) continue;
            if (getEnemyAt(x,y) != null) continue;
            if (getPickupAt(x,y) != null) continue;
            addPickupAt(x, y, PickupType.TELEPORT);
            return;
        }
        addPickupAt(px, py, PickupType.TELEPORT);
    }

    private void saveGame() {
        SaveData sd = new SaveData();
        sd.mission = mission;
        sd.px = px; sd.py = py; sd.hp = hp; sd.maxHP = maxHP;
        sd.quadTurns = quadTurns; sd.hasteTurns = hasteTurns;
        sd.map = map;
        sd.enemies = enemies;
        sd.pickups = pickups;
        sd.corpses = corpses;
        sd.fragCount = fragCount;
        sd.playerLevel = playerLevel;
        sd.currentWeapon = currentWeapon;
        sd.playerSavedTile = playerSavedTile;
        sd.playerSavedPickupIndex = playerSavedPickupIndex;
        sd.pistolMag = pistolMag; sd.pistolReserve = pistolReserve; sd.pistolAltReserve = pistolAltReserve;
        sd.shotgunMag = shotgunMag; sd.shotgunReserve = shotgunReserve;
        sd.railMag = railMag; sd.railReserve = railReserve; sd.railAltReserve = railAltReserve;
        sd.timesCleared = timesCleared;
        sd.grenades = grenades;
        try (XMLEncoder enc = new XMLEncoder(new BufferedOutputStream(new FileOutputStream("savegame.xml")))) {
            enc.writeObject(sd);
        } catch (Exception ex) { addMsg("Save failed."); }
    }

    private boolean loadGame() {
        File f = new File("savegame.xml");
        if (!f.exists()) return false;
        try (XMLDecoder dec = new XMLDecoder(new BufferedInputStream(new FileInputStream(f)))) {
            SaveData sd = (SaveData) dec.readObject();
            mission = sd.mission;
            px = sd.px; py = sd.py;
            maxHP = Math.min(sd.maxHP, 192);
            hp = Math.min(sd.hp, maxHP);
            quadTurns = sd.quadTurns; hasteTurns = sd.hasteTurns;
            if (sd.map != null) map = sd.map;
            enemies = sd.enemies != null ? sd.enemies : new ArrayList<>();
            pickups = sd.pickups != null ? sd.pickups : new ArrayList<>();
            corpses = sd.corpses != null ? sd.corpses : new ArrayList<>();
            fragCount = sd.fragCount;
            playerLevel = sd.playerLevel;
            currentWeapon = sd.currentWeapon;
            playerSavedTile = sd.playerSavedTile;
            playerSavedPickupIndex = sd.playerSavedPickupIndex;
            pistolMag = sd.pistolMag; pistolReserve = sd.pistolReserve; pistolAltReserve = sd.pistolAltReserve;
            shotgunMag = sd.shotgunMag; shotgunReserve = sd.shotgunReserve;
            railMag = sd.railMag; railReserve = sd.railReserve; railAltReserve = sd.railAltReserve;
            timesCleared = sd.timesCleared;
            grenades = sd.grenades;
            state = GameState.PLAYING;
            addMsg("Loaded timesCleared: " + timesCleared + " Grenades: " + grenades);
            return true;
        } catch (Exception ex) { return false; }
    }

    @Override
    protected void paintComponent(Graphics g0) {
        super.paintComponent(g0);
        Graphics2D g = (Graphics2D) g0.create();
        setPixelRenderingHints(g);

        switch (state) {
            case TITLE -> drawTitleScreen(g);
            case MENU -> drawMenu(g);
            case PLAYING, AIMING, LOOK, ANIMATING -> drawGame(g);
            case LOADING -> {
                drawGame(g);
                drawLoadingOverlay(g);
            }
            case GAME_OVER -> drawGameOver(g);
        }

        g.dispose();
    }

    private void drawTitleScreen(Graphics2D g) {
        int w = getWidth(), h = getHeight();
        g.setColor(Color.BLACK);
        g.fillRect(0,0,w,h);

        Color screenTextColor = new Color(0, 200, 0);

        drawCenteredRL(g, "Roguelike Arena V11", w/2, h/2 - 120, 24, screenTextColor);
        drawCenteredRL(g, "Easy Mode", w/2, h/2 - 88, 16, screenTextColor);

        int cx = w/2;
        int baseY = h/2 - 40;
        drawCenteredRL(g, "Controls / Keys", cx, baseY, 14, screenTextColor);
        drawCenteredRL(g, "Movement: Arrow keys or Numpad (1-9)", cx, baseY + 28, 12, screenTextColor);
        drawCenteredRL(g, "SPACE - Aim | ENTER - Shoot/Confirm | L - Look", cx, baseY + 52, 12, screenTextColor);
        drawCenteredRL(g, "W - Wait (consume one turn) | R - Reload (consume one turn)", cx, baseY + 76, 12, screenTextColor);
        drawCenteredRL(g, "O - Open door | C - Close door | T - Toggle door (aim mode)", cx, baseY + 100, 12, screenTextColor);
        drawCenteredRL(g, "1/2/3 - Switch weapons | F5 - Save | F9 - Load", cx, baseY + 124, 12, screenTextColor);
        drawCenteredRL(g, "G - Throw grenade (in aim mode) - consumes your action for the turn", cx, baseY + 148, 12, screenTextColor);

        drawCenteredRL(g, "ENTER - Menu  ESC - Quit", w/2, h/2 + 160, 14, screenTextColor);
    }

    private void drawMenu(Graphics2D g) {
        int w = getWidth(), h = getHeight();
        g.setColor(Color.BLACK);
        g.fillRect(0,0,w,h);

        Color screenTextColor = new Color(0, 200, 0);

        drawCenteredRL(g, "Menu", w/2, h/2 - 80, 20, screenTextColor);
        drawCenteredRL(g, "ENTER - Start Mission", w/2, h/2 - 40, 14, screenTextColor);
        drawCenteredRL(g, "ESC - Back to Title", w/2, h/2 - 16, 14, screenTextColor);
        drawCenteredRL(g, "F5 - Save | F9 - Load", w/2, h/2 + 8, 12, screenTextColor);
        drawCenteredRL(g, "SPACE - Enter game (if in menu)", w/2, h/2 + 40, 12, screenTextColor);
    }

    private void drawLoadingOverlay(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        setPixelRenderingHints(g);
        if (!loadingVisible) { g.dispose(); return; }
        drawCenteredRL(g, "LOADING", getWidth()/2, getHeight()/2, 20, new Color(0,200,0));
        g.dispose();
    }

    private void drawGame(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        setPixelRenderingHints(g);

        int w = getWidth(), h = getHeight();
        int centerX = w/2, centerY = h/2;
        int half = VIEW_SIZE/2;
        int vpW = VIEW_SIZE * TILE_PX;
        int vpH = VIEW_SIZE * TILE_PX;
        int offsetX = centerX - vpW/2;
        int offsetY = centerY - vpH/2;

        g.setColor(Color.BLACK); g.fillRect(0,0,w,h);

        for (int vy = 0; vy < VIEW_SIZE; vy++) {
            for (int vx = 0; vx < VIEW_SIZE; vx++) {
                int tx = px - half + vx;
                int ty = py - half + vy;
                int sx = offsetX + vx * TILE_PX;
                int sy = offsetY + vy * TILE_PX;
                if (tx<0||ty<0||tx>=MAP_W||ty>=MAP_H) {
                    g.setColor(Color.DARK_GRAY);
                    g.fillRect(sx, sy, TILE_PX, TILE_PX);
                    continue;
                }
                int t = map[ty][tx];
                switch (t) {
                    case FLOOR -> {
                        g.setColor(new Color(30,30,30));
                        g.fillRect(sx, sy, TILE_PX, TILE_PX);
                        drawCharRL(g, '.', sx, sy, TILE_PX, Color.GRAY);
                    }
                    case WALL -> g.setColor(new Color(80,80,80));
                    case DOOR_CLOSED -> g.setColor(new Color(120,80,40));
                    case DOOR_OPEN -> g.setColor(new Color(160,120,80));
                    default -> g.setColor(new Color(30,30,30));
                }
                if (t != FLOOR) {
                    g.fillRect(sx, sy, TILE_PX, TILE_PX);
                }
            }
        }

        // Draw active projectiles (including grenades in flight)
        for (TileProjectile tp : new ArrayList<>(activeProjectiles)) {
            if (tp.path == null || tp.path.isEmpty()) continue;
            if (tp.fromPlayer && tp.remainingTurns == 0) continue;
            Point p = tp.current();
            int relX = p.x - (px - half);
            int relY = p.y - (py - half);
            if (relX >= 0 && relX < VIEW_SIZE && relY >= 0 && relY < VIEW_SIZE) {
                int sx = offsetX + relX * TILE_PX;
                int sy = offsetY + relY * TILE_PX;
                // use projectile's color (for '*' this will be brass)
                drawCharRL(g, tp.ch, sx, sy, TILE_PX, tp.color != null ? tp.color : Color.GRAY);
            }
        }

        Iterator<Effect> it = effects.iterator();
        while (it.hasNext()) {
            Effect ef = it.next();
            ef.life--;
            if (ef.life <= 0) { it.remove(); continue; }
            if (ef.ch == 'x') continue;
            int relX = ef.x - (px - half);
            int relY = ef.y - (py - half);
            if (relX >= 0 && relX < VIEW_SIZE && relY >= 0 && relY < VIEW_SIZE) {
                int sx = offsetX + relX * TILE_PX;
                int sy = offsetY + relY * TILE_PX;
                if (ef.fullTile) {
                    Color c = new Color(ef.color.getRed(), ef.color.getGreen(), ef.color.getBlue(), Math.max(0, Math.min(255, ef.alpha)));
                    Composite old = g.getComposite();
                    g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, c.getAlpha()/255f));
                    g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue()));
                    g.fillRect(sx, sy, TILE_PX, TILE_PX);
                    g.setComposite(old);
                } else {
                    drawCharRL(g, ef.ch, sx, sy, TILE_PX, ef.color);
                }
            }
        }

        // Draw entities: player, enemies, corpses, pickups
        for (int vy = 0; vy < VIEW_SIZE; vy++) {
            for (int vx = 0; vx < VIEW_SIZE; vx++) {
                int tx = px - half + vx;
                int ty = py - half + vy;
                int sx = offsetX + vx * TILE_PX;
                int sy = offsetY + vy * TILE_PX;
                if (tx<0||ty<0||tx>=MAP_W||ty>=MAP_H) continue;

                if (tx==px && ty==py) {
                    drawCharRL(g, '@', sx, sy, TILE_PX, new Color(200,200,200));
                } else {
                    Enemy e = getEnemyAt(tx, ty);
                    if (e != null && e.alive) {
                        Color ec = new Color(0, 200, 0);
                        char ch = switch (e.type) {
                            case GRUNT -> 'g';
                            case SHOOTER -> 's';
                            case FAST -> 'f';
                            case TANK -> 'T';
                            case BOSS -> (e.name != null && e.name.equals("KONFI")) ? 'K' : 'C';
                            default -> '?';
                        };
                        drawCharRL(g, ch, sx, sy, TILE_PX, ec);
                    } else {
                        // draw corpse if present
                        Corpse cr = getCorpseAt(tx, ty);
                        if (cr != null) {
                            drawCharRL(g, cr.ch, sx, sy, TILE_PX, new Color(160, 40, 40));
                        }
                        Pickup pu = getPickupAt(tx, ty);
                        if (pu != null && pu.active) {
                            char ch = switch (pu.type) {
                                case MEDPACK -> '+';
                                case QUAD -> 'Q';
                                case HASTE -> 'H';
                                case MEGA -> 'M';
                                case TELEPORT -> 'O';
                                case GRENADE -> 'G';
                                default -> '!';
                            };
                            Color pc;
                            if (pu.type == PickupType.TELEPORT) pc = new Color(80,160,255);
                            else if (pu.type == PickupType.GRENADE) pc = GRENADE_COLOR;
                            else pc = (ch == '!') ? COLOR_PICKUP_BLUE : Color.WHITE;
                            drawCharRL(g, ch, sx, sy, TILE_PX, pc);
                        }

                    }
                }

            }
        }

        drawStringRL(g, "HP: " + hp + "/" + maxHP + "  Level: " + playerLevel + "  Frags: " + fragCount, 10, 10, 12, Color.WHITE);
        drawStringRL(g, "Mission: " + mission + "/" + MAX_MISSION + "  Shots: " + shotsRemaining + "/" + SHOTS_PER_TURN, 10, 22, 12, Color.WHITE);

        drawStringRL(g, "Pistol: " + pistolMag + "/" + PISTOL_MAG_SIZE + " (R:" + pistolReserve + " H:" + pistolAltReserve + ")", 10, 36, 12, Color.WHITE);
        drawStringRL(g, "Shotgun: " + shotgunMag + "/" + SHOTGUN_MAG_SIZE + " (R:" + shotgunReserve + ")", 10, 48, 12, Color.WHITE);
        drawStringRL(g, "Rail: " + railMag + "/" + RAIL_MAG_SIZE + " (R:" + railReserve + " H:" + railAltReserve + ")", 10, 60, 12, Color.WHITE);
        drawStringRL(g, "Grenades: " + grenades + " (press G in aim mode to throw; consumes your action)", 10, 74, 12, GRENADE_COLOR);

        int baseY = getHeight() - 100;
        for (int i = 0; i < msgLines.length; i++) {
            Color c = (i==0) ? Color.WHITE : (i==1) ? Color.LIGHT_GRAY : new Color(120,120,120);
            drawStringRL(g, msgLines[i] == null ? "" : msgLines[i], 10, baseY + i*14, 12, c);
        }

        if (state == GameState.AIMING) {
            int relX = aimX - (px - half);
            int relY = aimY - (py - half);
            if (relX >= 0 && relX < VIEW_SIZE && relY >= 0 && relY < VIEW_SIZE) {
                int sx = offsetX + relX * TILE_PX;
                int sy = offsetY + relY * TILE_PX;
                g.setColor(Color.YELLOW);
                g.drawRect(sx, sy, TILE_PX-1, TILE_PX-1);
            }
        }

        if (state == GameState.LOOK) {
            int relX = lookX - (px - half);
            int relY = lookY - (py - half);
            if (relX >= 0 && relX < VIEW_SIZE && relY >= 0 && relY < VIEW_SIZE) {
                int sx = offsetX + relX * TILE_PX;
                int sy = offsetY + relY * TILE_PX;
                g.setColor(new Color(80,160,255));
                g.drawRect(sx, sy, TILE_PX-1, TILE_PX-1);
            }
        }

        for (Effect ef : new ArrayList<>(effects)) {
            if (ef.ch != 'x') continue;
            int relX = ef.x - (px - half);
            int relY = ef.y - (py - half);
            if (relX >= 0 && relX < VIEW_SIZE && relY >= 0 && relY < VIEW_SIZE) {
                int sx = offsetX + relX * TILE_PX;
                int sy = offsetY + relY * TILE_PX;
                drawCharRL(g, ef.ch, sx, sy, TILE_PX, Color.RED);
            }
        }

        g.dispose();
    }

    private void drawGameOver(Graphics g0) {
        drawGame(g0);
        Graphics2D g = (Graphics2D) g0.create();
        setPixelRenderingHints(g);
        drawCenteredRL(g, "GAME OVER", getWidth()/2, getHeight()/2 - 20, 20, new Color(0,200,0));
        drawCenteredRL(g, "ENTER - Back to Menu  ESC - Quit", getWidth()/2, getHeight()/2 + 20, 12, new Color(0,200,0));
        g.dispose();
    }

    private void waitTurn() {
        if (state != GameState.PLAYING) return;
        addMsg("You wait one turn.");
        advanceProjectilesAfterPlayerMove();
        enemyTurn();
    }

    private void reloadWeapon() {
        if (state != GameState.PLAYING) return;
        boolean didReload = false;
        switch (currentWeapon) {
            case 0 -> {
                if (pistolMag < PISTOL_MAG_SIZE && pistolReserve > 0) {
                    int need = PISTOL_MAG_SIZE - pistolMag;
                    int take = Math.min(need, pistolReserve);
                    pistolMag += take;
                    pistolReserve -= take;
                    didReload = true;
                    addMsg("Reloaded Pistol: +" + take + " to mag (" + pistolMag + "/" + PISTOL_MAG_SIZE + "), reserve: " + pistolReserve);
                } else addMsg("Pistol mag full or no reserve.");
            }
            case 1 -> {
                if (shotgunMag < SHOTGUN_MAG_SIZE && shotgunReserve > 0) {
                    int need = SHOTGUN_MAG_SIZE - shotgunMag;
                    int take = Math.min(need, shotgunReserve);
                    shotgunMag += take;
                    shotgunReserve -= take;
                    didReload = true;
                    addMsg("Reloaded Shotgun: +" + take + " to mag (" + shotgunMag + "/" + SHOTGUN_MAG_SIZE + "), reserve: " + shotgunReserve);
                } else addMsg("Shotgun mag full or no reserve.");
            }
            case 2 -> {
                if (railMag < RAIL_MAG_SIZE && railReserve > 0) {
                    int need = RAIL_MAG_SIZE - railMag;
                    int take = Math.min(need, railReserve);
                    railMag += take;
                    railReserve -= take;
                    didReload = true;
                    addMsg("Reloaded Rail: +" + take + " to mag (" + railMag + "/" + RAIL_MAG_SIZE + "), reserve: " + railReserve);
                } else addMsg("Rail mag full or no reserve.");
            }
            default -> addMsg("Reloaded.");
        }
        if (didReload) sfxReload();
        advanceProjectilesAfterPlayerMove();
        enemyTurn();
    }

    // --- Grenade animation and explosion handling ---

    private void animateGrenade(TileProjectile tp) {
        if (tp == null || tp.path == null || tp.path.isEmpty()) return;
        // Use a Swing Timer to animate grenade flight tile-by-tile
        tp.index = 0;
        tp.timer = new Timer(tp.msPerTile, ev -> {
            tp.index++;
            // play ticking sound occasionally
            sfxGrenadeTick();
            if (tp.index >= tp.path.size()) {
                // explode at last tile
                Point p = tp.path.get(tp.path.size()-1);
                explodeGrenadeAt(p.x, p.y);
                if (tp.timer != null) tp.timer.stop();
                activeProjectiles.remove(tp);
            } else {
                // if grenade hits a wall tile mid-flight, explode there
                Point p = tp.path.get(tp.index);
                if (!isWalkable(p.x, p.y)) {
                    explodeGrenadeAt(p.x, p.y);
                    if (tp.timer != null) tp.timer.stop();
                    activeProjectiles.remove(tp);
                } else {
                    // continue flight; repaint to show grenade in new position
                    repaint();
                }

            }
        });
        tp.timer.setRepeats(true);
        tp.timer.start();
    }

    private void explodeGrenadeAt(int cx, int cy) {
        sfxGrenadeBoom();
        addMsg("Grenade explodes!");
        // create flashing 3x3 full-tile effects and damage enemies and player
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                int tx = cx + dx, ty = cy + dy;
                if (tx < 0 || ty < 0 || tx >= MAP_W || ty >= MAP_H) continue;
                // add a flashing full-tile effect (alpha will fade)
                effects.add(new Effect(tx, ty, 18, new Color(255, 160, 64), ' ', true, 200));
            }
        }

        // DAMAGE PLAYER if in 3x3 area (this is the requested change)
        if (Math.max(Math.abs(px - cx), Math.abs(py - cy)) <= 1) {
            int pdmg = 12; // same base damage as enemies
            if (quadTurns > 0) pdmg *= 4;
            hp -= pdmg;
            effects.add(new Effect(px, py, 12, Color.ORANGE, 'x', false, 255));
            sfxHit();
            addMsg("You are hit by the grenade for " + pdmg + " HP (" + hp + "/" + maxHP + ")");
            if (hp <= 0) {
                hp = 0;
                addMsg("You died.");
                state = GameState.GAME_OVER;
                // still continue to apply enemy deaths/effects for consistency
            }
        }

        // damage enemies in 3x3
        for (Enemy e : enemies) {
            if (!e.alive) continue;
            if (Math.max(Math.abs(e.x - cx), Math.abs(e.y - cy)) <= 1) {
                int dmg = 12; // grenade base damage
                if (quadTurns > 0) dmg *= 4;
                e.hp -= dmg;
                effects.add(new Effect(e.x, e.y, 12, Color.ORANGE, 'x', false, 255));
                if (e.hp <= 0) {
                    e.alive = false;
                    fragCount++;
                    addMsg("Enemy blown apart! Frags: " + fragCount);
                    Corpse corpse = new Corpse(e.x, e.y);
                    corpses.add(corpse);
                    // chance to drop pickups on corpse (including grenade)
                    if (rng.nextDouble() < 0.25) addPickupAt(e.x, e.y, PickupType.BULLETS);
                    if (rng.nextDouble() < 0.10) addPickupAt(e.x, e.y, PickupType.GRENADE);
                    checkLevelUp();
                }
            }
        }
        // create small lingering sparks in the center tile
        effects.add(new Effect(cx, cy, 30, Color.YELLOW, '*', false, 255));
        repaint();
    }

    private static void createAndShow() {
        JFrame f = new JFrame("Roguelike Arena V11 - Go through a few times");
        RoguelikeArenaV11 panel = new RoguelikeArenaV11();
        f.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        f.getContentPane().add(panel);
        f.pack();
        f.setLocationRelativeTo(null);
        f.setVisible(true);

        // Start background loop (new): plik V11Loop.wav powinien znajdować się obok jar/exe lub w working dir
        panel.startBackgroundLoop("V11Loop.wav");

        // Ensure background loop stops when window is closing
        f.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                panel.stopBackgroundLoop();
            }
            @Override
            public void windowClosed(WindowEvent e) {
                panel.stopBackgroundLoop();
            }
        });

        new Timer(40, ev -> panel.repaint()).start();
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(RoguelikeArenaV11::createAndShow);
    }
}
