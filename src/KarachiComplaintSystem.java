import javax.swing.*;
import javax.swing.table.*;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;

/* ---------- Data model ---------- */
class Complaint implements Comparable<Complaint>, Serializable {
    public final int id;
    public final String type;
    public final String area;
    public final String description;
    public final int severity;
    public LocalDateTime timestamp;
    public String status;

    public Complaint(int id, String type, String area, String description, int severity, LocalDateTime ts) {
        this.id = id;
        this.type = type;
        this.area = area;
        this.description = description;
        this.severity = severity;
        this.timestamp = ts;
        this.status = "Pending";
    }

    @Override
    public int compareTo(Complaint other) {
        if (this.severity != other.severity) return Integer.compare(this.severity, other.severity);
        return other.timestamp.compareTo(this.timestamp); // earlier has higher priority
    }

    public String getKeyForDuplicate() {
        return (area == null ? "" : area.trim().toLowerCase()) + "|" + (description == null ? "" : description.trim().toLowerCase());
    }

    public String toCSV() {
        String ar = (area == null ? "" : area.replace(",", ";"));
        String desc = (description == null ? "" : description.replace(",", ";"));
        return id + "," + type + "," + ar + "," + desc + "," + severity + "," + timestamp.toString() + "," + status;
    }

    @Override
    public String toString() {
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        return String.format("ID: %d  |  Type: %s  |  Area: %s  |  Severity: %d  |  Time: %s  |  Status: %s\nDesc: %s",
                id, type, area, severity, timestamp.format(fmt), status, description);
    }
}

/* ---------- Simple singly linked list ---------- */
class Node<T> { T data; Node<T> next; Node(T d){ data = d; } }
class MyList<T> {
    private Node<T> head = null, tail = null;
    private int size = 0;
    public void addLast(T d) {
        Node<T> n = new Node<>(d);
        if (tail == null) head = tail = n;
        else { tail.next = n; tail = n; }
        size++;
    }
    public boolean remove(T d) {
        Node<T> prev = null, cur = head;
        while (cur != null) {
            if (cur.data.equals(d)) {
                if (prev == null) head = cur.next;
                else prev.next = cur.next;
                if (cur == tail) tail = prev;
                size--;
                return true;
            }
            prev = cur; cur = cur.next;
        }
        return false;
    }
    public void forEach(java.util.function.Consumer<T> c) {
        Node<T> cur = head;
        while (cur != null) { c.accept(cur.data); cur = cur.next; }
    }
    public int size() { return size; }
}

/* ---------- Priority queue (max-heap) ---------- */
class MaxHeap {
    private Complaint[] heap;
    private int size = 0;
    public MaxHeap(int capacity) { heap = new Complaint[capacity + 1]; } // 1-indexed
    private void ensure() {
        if (size + 1 >= heap.length) {
            Complaint[] n = new Complaint[heap.length * 2];
            System.arraycopy(heap, 0, n, 0, heap.length);
            heap = n;
        }
    }
    public void add(Complaint c) {
        ensure();
        heap[++size] = c;
        siftUp(size);
    }
    public Complaint poll() {
        if (size == 0) return null;
        Complaint top = heap[1];
        heap[1] = heap[size]; heap[size] = null; size--;
        siftDown(1);
        return top;
    }
    public int size() { return size; }
    public boolean isEmpty() { return size == 0; }
    private void siftUp(int i) {
        while (i > 1) {
            int p = i / 2;
            if (heap[i].compareTo(heap[p]) > 0) {
                Complaint tmp = heap[i]; heap[i] = heap[p]; heap[p] = tmp;
                i = p;
            } else break;
        }
    }
    private void siftDown(int i) {
        while (true) {
            int l = i * 2, r = l + 1, maxi = i;
            if (l <= size && heap[l].compareTo(heap[maxi]) > 0) maxi = l;
            if (r <= size && heap[r].compareTo(heap[maxi]) > 0) maxi = r;
            if (maxi != i) {
                Complaint tmp = heap[i]; heap[i] = heap[maxi]; heap[maxi] = tmp;
                i = maxi;
            } else break;
        }
    }
}

/* ---------- Severity renderer ---------- */
class SeverityRenderer extends DefaultTableCellRenderer {
    @Override
    public Component getTableCellRendererComponent(JTable table, Object value,
                                                   boolean isSelected, boolean hasFocus,
                                                   int row, int column) {
        Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
        try {
            Object sevObj = table.getValueAt(row, 3);
            int sev = Integer.parseInt(sevObj.toString());
            if (sev <= 2) c.setBackground(new Color(180, 255, 180));
            else if (sev == 3) c.setBackground(new Color(255, 235, 150));
            else c.setBackground(new Color(255, 170, 170));
        } catch (Exception e) {
            c.setBackground(Color.WHITE);
        }
        if (isSelected) c.setBackground(c.getBackground().darker());
        return c;
    }
}

/* ---------- Main Application ---------- */
public class KarachiComplaintSystem extends JFrame {
    private final MyList<Complaint> storage = new MyList<>();
    private final MaxHeap priorityQueue = new MaxHeap(64);
    private final LinkedList<Complaint> fifoQueue = new LinkedList<>();
    private final Map<String, Integer> duplicateMap = new HashMap<>();
    private int nextId = 1;

    private final String CSV_FILE = "complaints_data.csv";

    private CardLayout cardLayout;
    private JPanel cards;

    private DefaultTableModel tableModel;
    private JTable mainTable;
    private DefaultTableModel reportModel;
    private JTable reportTable;

    private JTextField addAreaField, addDescField, searchField, deleteField;
    private JComboBox<String> addTypeCombo;
    private JSpinner severitySpinner;
    private JRadioButton servePriorityRadio, serveFifoRadio;

    private JButton selectedButton = null;

    public KarachiComplaintSystem() {
        super("Karachi Complaint Management");
        setSize(980, 640);
        setLocationRelativeTo(null);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLayout(new BorderLayout());
        initUI();

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                saveToCSV(CSV_FILE); // save on exit
            }
        });
    }

    private void initUI() {
        JPanel leftMenu = buildLeftMenu();
        cards = new JPanel();
        cardLayout = new CardLayout();
        cards.setLayout(cardLayout);

        cards.add(buildAddPanel(), "add");
        cards.add(buildViewPanel(), "view");
        cards.add(buildSearchPanel(), "search");
        cards.add(buildDeletePanel(), "delete");
        cards.add(buildServePanel(), "serve");
        cards.add(buildReportPanel(), "report");
        cardLayout.show(cards, "view");

        add(leftMenu, BorderLayout.WEST);
        add(cards, BorderLayout.CENTER);
    }

    private JPanel buildLeftMenu() {
        JPanel left = new JPanel();
        left.setPreferredSize(new Dimension(220, 0));
        left.setLayout(new BoxLayout(left, BoxLayout.Y_AXIS));
        left.setBorder(BorderFactory.createEmptyBorder(20, 15, 20, 15));
        left.setBackground(new Color(45, 52, 54));

        // Header
        JLabel header = new JLabel("MENU");
        header.setFont(new Font("Segoe UI", Font.BOLD, 16));
        header.setForeground(new Color(220, 221, 225));
        header.setAlignmentX(Component.LEFT_ALIGNMENT);
        header.setBorder(BorderFactory.createEmptyBorder(0, 10, 15, 0));
        left.add(header);

        left.add(Box.createRigidArea(new Dimension(0, 5)));

        addMenuButton(left, "➕ Add Complaint", e -> showCard("add"));
        addMenuButton(left, "📋 View All", e -> { refreshMainTable(); showCard("view"); });
        addMenuButton(left, "🔍 Search", e -> showCard("search"));
        addMenuButton(left, "🗑️ Delete", e -> showCard("delete"));
        addMenuButton(left, "✓ Serve Complaint", e -> showCard("serve"));
        addMenuButton(left, "📊 Pending Report", e -> { refreshReportTable(); showCard("report"); });

        left.add(Box.createVerticalGlue());
        
        addMenuButton(left, "💾 Exit (autosave)", e -> { saveToCSV(CSV_FILE); System.exit(0); });

        return left;
    }

    private void addMenuButton(JPanel parent, String text, ActionListener al) {
        JButton b = new JButton(text);
        b.setFont(new Font("Segoe UI", Font.PLAIN, 14));
        b.setForeground(new Color(220, 221, 225));
        b.setBackground(new Color(54, 61, 63));
        b.setFocusPainted(false);
        b.setBorderPainted(false);
        b.setAlignmentX(Component.LEFT_ALIGNMENT);
        b.setMaximumSize(new Dimension(Integer.MAX_VALUE, 45));
        b.setHorizontalAlignment(SwingConstants.LEFT);
        b.setBorder(BorderFactory.createEmptyBorder(10, 15, 10, 15));
        b.setCursor(new Cursor(Cursor.HAND_CURSOR));
        
        b.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                if (b != selectedButton) {
                    b.setBackground(new Color(99, 110, 114));
                }
            }
            
            @Override
            public void mouseExited(MouseEvent e) {
                if (b != selectedButton) {
                    b.setBackground(new Color(54, 61, 63));
                }
            }
        });
        
        b.addActionListener(e -> {
            if (selectedButton != null) {
                selectedButton.setBackground(new Color(54, 61, 63));
            }
            b.setBackground(new Color(116, 185, 255));
            selectedButton = b;
            al.actionPerformed(e);
        });
        
        parent.add(b);
        parent.add(Box.createRigidArea(new Dimension(0, 8)));
    }

    /* ---------- Panels ---------- */
    private JPanel buildAddPanel() {
        JPanel p = new JPanel(new BorderLayout(12,12));
        p.setBorder(BorderFactory.createEmptyBorder(20,20,20,20));
        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(8,8,8,8);
        c.anchor = GridBagConstraints.WEST;

        c.gridx = 0; c.gridy = 0;
        form.add(new JLabel("Type:"), c);
        c.gridx = 1;
        addTypeCombo = new JComboBox<>(new String[]{"water","electricity","garbage","traffic","other"});
        addTypeCombo.setPreferredSize(new Dimension(220, 26));
        form.add(addTypeCombo, c);

        c.gridx = 0; c.gridy = 1;
        form.add(new JLabel("Area:"), c);
        c.gridx = 1;
        addAreaField = new JTextField();
        addAreaField.setPreferredSize(new Dimension(220, 26));
        form.add(addAreaField, c);

        c.gridx = 0; c.gridy = 2;
        form.add(new JLabel("Description:"), c);
        c.gridx = 1;
        addDescField = new JTextField();
        addDescField.setPreferredSize(new Dimension(220, 26));
        form.add(addDescField, c);

        c.gridx = 0; c.gridy = 3;
        form.add(new JLabel("Severity (1-5):"), c);
        c.gridx = 1;
        severitySpinner = new JSpinner(new SpinnerNumberModel(3, 1, 5, 1));
        form.add(severitySpinner, c);

        c.gridx = 0; c.gridy = 4; c.gridwidth = 2;
        JButton addBtn = new JButton("Add Complaint");
        addBtn.setBackground(new Color(70, 130, 180));
        addBtn.setForeground(Color.WHITE);
        addBtn.addActionListener(e -> onAddComplaint());
        form.add(addBtn, c);

        p.add(form, BorderLayout.NORTH);
        return p;
    }

    private JPanel buildViewPanel() {
        JPanel p = new JPanel(new BorderLayout(8,8));
        p.setBorder(BorderFactory.createEmptyBorder(12,12,12,12));

        tableModel = new DefaultTableModel(new Object[]{"ID","Type","Area","Severity","Time","Status","Description"}, 0) {
            @Override public boolean isCellEditable(int row, int column) { return false; }
        };
        mainTable = new JTable(tableModel);
        mainTable.setAutoCreateRowSorter(true);
        mainTable.setFillsViewportHeight(true);
        mainTable.setRowHeight(26);

        TableColumnModel tcm = mainTable.getColumnModel();
        tcm.getColumn(3).setCellRenderer(new SeverityRenderer()); // severity coloring
        tcm.getColumn(0).setPreferredWidth(50);
        tcm.getColumn(6).setPreferredWidth(350);

        JScrollPane sp = new JScrollPane(mainTable);
        p.add(sp, BorderLayout.CENTER);

        refreshMainTable();
        return p;
    }

    private JPanel buildSearchPanel() {
        JPanel p = new JPanel(new BorderLayout(8,8));
        p.setBorder(BorderFactory.createEmptyBorder(20,20,20,20));
        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 10));
        top.add(new JLabel("Enter ID:"));
        searchField = new JTextField(10);
        top.add(searchField);
        JButton b = new JButton("Search");
        JTextArea output = new JTextArea(8, 60);
        output.setEditable(false);
        b.addActionListener(e -> {
            try {
                int id = Integer.parseInt(searchField.getText().trim());
                Complaint c = searchById(id);
                if (c == null) output.setText("Not found ID " + id);
                else output.setText(c.toString());
            } catch (NumberFormatException ex) {
                JOptionPane.showMessageDialog(this, "Enter numeric ID.");
            }
        });
        top.add(b);
        p.add(top, BorderLayout.NORTH);
        p.add(new JScrollPane(output), BorderLayout.CENTER);
        return p;
    }

    private JPanel buildDeletePanel() {
        JPanel p = new JPanel(new BorderLayout(8,8));
        p.setBorder(BorderFactory.createEmptyBorder(20,20,20,20));
        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 10));
        top.add(new JLabel("ID to delete:"));
        deleteField = new JTextField(8);
        top.add(deleteField);
        JButton delBtn = new JButton("Delete");
        delBtn.addActionListener(e -> {
            try {
                int id = Integer.parseInt(deleteField.getText().trim());
                boolean ok = deleteById(id);
                if (ok) { JOptionPane.showMessageDialog(this, "Deleted ID " + id); refreshMainTable(); }
                else JOptionPane.showMessageDialog(this, "ID not found: " + id);
            } catch (NumberFormatException ex) { JOptionPane.showMessageDialog(this, "Enter numeric ID."); }
        });
        top.add(delBtn);
        p.add(top, BorderLayout.NORTH);
        return p;
    }

    private JPanel buildServePanel() {
        JPanel p = new JPanel(new BorderLayout(8,8));
        p.setBorder(BorderFactory.createEmptyBorder(20,20,20,20));
        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 12));
        servePriorityRadio = new JRadioButton("Priority (severity >= 4)", true);
        serveFifoRadio = new JRadioButton("FIFO (first-come-first-served)");
        ButtonGroup bg = new ButtonGroup(); bg.add(servePriorityRadio); bg.add(serveFifoRadio);
        JButton serveBtn = new JButton("Serve Next");
        JTextArea out = new JTextArea(8, 60); out.setEditable(false);
        serveBtn.addActionListener(e -> {
            boolean usePriority = servePriorityRadio.isSelected();
            Complaint s = serveNext(usePriority);
            if (s == null) out.setText("No complaints to serve.");
            else out.setText("Served:\n" + s.toString());
            refreshMainTable();
        });
        top.add(servePriorityRadio); top.add(serveFifoRadio); top.add(serveBtn);
        p.add(top, BorderLayout.NORTH);
        p.add(new JScrollPane(out), BorderLayout.CENTER);
        return p;
    }

    private JPanel buildReportPanel() {
        JPanel p = new JPanel(new BorderLayout(8,8));
        p.setBorder(BorderFactory.createEmptyBorder(12,12,12,12));
        reportModel = new DefaultTableModel(new Object[]{"ID","Type","Area","Severity","Time","Status","Description"},0) {
            @Override public boolean isCellEditable(int r,int c){ return false; }
        };
        reportTable = new JTable(reportModel);
        reportTable.setAutoCreateRowSorter(true);
        reportTable.setFillsViewportHeight(true);
        reportTable.setRowHeight(26);
        reportTable.getColumnModel().getColumn(3).setCellRenderer(new SeverityRenderer());
        reportTable.getColumnModel().getColumn(0).setPreferredWidth(50);
        reportTable.getColumnModel().getColumn(6).setPreferredWidth(350);
        
        JScrollPane sp = new JScrollPane(reportTable);
        p.add(sp, BorderLayout.CENTER);
        
        JButton refresh = new JButton("Refresh Report");
        refresh.setBackground(new Color(70, 130, 180));
        refresh.setForeground(Color.WHITE);
        refresh.addActionListener(e -> refreshReportTable());
        
        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        bottom.add(refresh);
        p.add(bottom, BorderLayout.SOUTH);
        return p;
    }

    /* ---------- Actions ---------- */

    private void onAddComplaint() {
        String type = (String) addTypeCombo.getSelectedItem();
        String area = addAreaField.getText().trim();
        String desc = addDescField.getText().trim();
        int sev = (Integer) severitySpinner.getValue();
        if (area.isEmpty() || desc.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Area and Description are required.");
            return;
        }
        Complaint c = new Complaint(nextId++, type, area, desc, sev, LocalDateTime.now());
        String key = c.getKeyForDuplicate();
        if (duplicateMap.containsKey(key)) {
            int existing = duplicateMap.get(key);
            int opt = JOptionPane.showConfirmDialog(this,
                    "A similar complaint (ID " + existing + ") exists. Add anyway?",
                    "Duplicate detected", JOptionPane.YES_NO_OPTION);
            if (opt != JOptionPane.YES_OPTION) return;
        }
        storage.addLast(c);
        duplicateMap.put(key, c.id);
        if (sev >= 4) priorityQueue.add(c);
        else fifoQueue.addLast(c);
        JOptionPane.showMessageDialog(this, "Added complaint ID " + c.id);
        addAreaField.setText("");
        addDescField.setText("");
        severitySpinner.setValue(3);
        refreshMainTable();
    }

    private Complaint serveNext(boolean usePriority) {
        Complaint c = null;
        if (usePriority) {
            c = priorityQueue.poll();
            if (c == null && !fifoQueue.isEmpty()) c = fifoQueue.removeFirst();
        } else {
            if (!fifoQueue.isEmpty()) c = fifoQueue.removeFirst();
            if (c == null) c = priorityQueue.poll();
        }
        if (c == null) return null;
        c.status = "Processed";
        duplicateMap.remove(c.getKeyForDuplicate());
        return c;
    }

    private Complaint searchById(int id) {
        final Complaint[] res = {null};
        storage.forEach(obj -> { if (obj.id == id) res[0] = obj; });
        return res[0];
    }

    private boolean deleteById(int id) {
        Complaint[] found = new Complaint[1];
        storage.forEach(obj -> { if (obj.id == id) found[0] = obj; });
        if (found[0] == null) return false;
        Complaint c = found[0];
        boolean ok = storage.remove(c);
        duplicateMap.remove(c.getKeyForDuplicate());
        rebuildQueuesExcluding(id);
        return ok;
    }

    private void rebuildQueuesExcluding(int excludeId) {
        MaxHeap newPQ = new MaxHeap(64);
        LinkedList<Complaint> newFIFO = new LinkedList<>();
        storage.forEach(obj -> {
            if (!"Pending".equals(obj.status)) return;
            if (obj.id == excludeId) return;
            if (obj.severity >= 4) newPQ.add(obj); else newFIFO.addLast(obj);
        });
        while (!priorityQueue.isEmpty()) priorityQueue.poll();
        fifoQueue.clear();
        Complaint cc;
        while ((cc = newPQ.poll()) != null) priorityQueue.add(cc);
        for (Complaint cf : newFIFO) fifoQueue.addLast(cf);
    }

    private List<Complaint> collectAllPending() {
        List<Complaint> res = new ArrayList<>();
        storage.forEach(obj -> { if ("Pending".equals(obj.status)) res.add(obj); });
        return res;
    }

    private void refreshMainTable() {
        if (tableModel == null) return;
        tableModel.setRowCount(0);
        storage.forEach(c -> {
            tableModel.addRow(new Object[]{c.id, c.type, c.area, c.severity, c.timestamp.toString(), c.status, c.description});
        });
    }

    private void refreshReportTable() {
        if (reportModel == null) return;
        reportModel.setRowCount(0);
        List<Complaint> pending = collectAllPending();
        pending.sort((a,b)-> {
            if (b.severity != a.severity) return Integer.compare(b.severity, a.severity);
            return a.timestamp.compareTo(b.timestamp);
        });
        for (Complaint c : pending) {
            reportModel.addRow(new Object[]{c.id, c.type, c.area, c.severity, c.timestamp.toString(), c.status, c.description});
        }
    }

    /* ---------- Persistence ---------- */
    private void saveToCSV(String filename) {
        try (PrintWriter pw = new PrintWriter(new FileWriter(filename))) {
            pw.println("id,type,area,description,severity,timestamp,status");
            storage.forEach(c -> pw.println(c.toCSV()));
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, "Save error: " + e.getMessage());
        }
    }

    private void showCard(String name) {
        cardLayout.show(cards, name);
    }

    /* ---------- Main ---------- */
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); } catch (Exception ignored) {}
            KarachiComplaintSystem app = new KarachiComplaintSystem();
            app.setVisible(true);
        });
    }
}