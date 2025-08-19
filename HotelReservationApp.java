import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * CodeAlpha - TASK 4: Hotel Reservation System (Console, OOP + File I/O)
 * Features:
 *  - Search availability by date range & room type
 *  - Book & cancel reservations
 *  - Payment simulation
 *  - View reservation details & all bookings
 *  - CSV persistence (rooms.csv, reservations.csv)
 *
 * Compile: javac HotelReservationApp.java
 * Run:     java HotelReservationApp
 */
public class HotelReservationApp {

    // ====== Models ======
    enum RoomType { STANDARD, DELUXE, SUITE }

    static class Room {
        final int id;
        final RoomType type;
        final double pricePerNight;

        Room(int id, RoomType type, double pricePerNight) {
            this.id = id;
            this.type = type;
            this.pricePerNight = pricePerNight;
        }

        String toCsv() { return id + "," + type.name() + "," + pricePerNight; }

        static Room fromCsv(String line) {
            String[] t = line.split(",");
            return new Room(Integer.parseInt(t[0]), RoomType.valueOf(t[1]), Double.parseDouble(t[2]));
        }

        @Override public String toString() {
            return String.format("Room #%d | %-8s | ₹%.2f/night", id, type.name(), pricePerNight);
        }
    }

    static class Reservation {
        final UUID id;
        final String guestName;
        final int roomId;
        final LocalDate checkIn;
        final LocalDate checkOut; // exclusive
        boolean paid;
        final double totalAmount;

        Reservation(UUID id, String guestName, int roomId, LocalDate checkIn, LocalDate checkOut, boolean paid, double totalAmount) {
            this.id = id;
            this.guestName = guestName;
            this.roomId = roomId;
            this.checkIn = checkIn;
            this.checkOut = checkOut;
            this.paid = paid;
            this.totalAmount = totalAmount;
        }

        long nights() {
            return Duration.between(checkIn.atStartOfDay(), checkOut.atStartOfDay()).toDays();
        }

        String toCsv() {
            return String.join(",",
                    id.toString(),
                    guestName.replace(",", " "),
                    String.valueOf(roomId),
                    checkIn.toString(),
                    checkOut.toString(),
                    String.valueOf(paid),
                    String.valueOf(totalAmount)
            );
        }

        static Reservation fromCsv(String line) {
            String[] t = line.split(",");
            return new Reservation(
                    UUID.fromString(t[0]),
                    t[1],
                    Integer.parseInt(t[2]),
                    LocalDate.parse(t[3]),
                    LocalDate.parse(t[4]),
                    Boolean.parseBoolean(t[5]),
                    Double.parseDouble(t[6])
            );
        }

        @Override public String toString() {
            return """
                   Reservation %s
                   Guest      : %s
                   Room ID    : %d
                   Check-In   : %s
                   Check-Out  : %s
                   Nights     : %d
                   Paid       : %s
                   Amount     : ₹%.2f
                   """.formatted(id, guestName, roomId, checkIn, checkOut, nights(), paid ? "YES" : "NO", totalAmount);
        }
    }

    // ====== Hotel Service with Persistence ======
    static class Hotel {
        private final Map<Integer, Room> rooms = new HashMap<>();
        private final Map<UUID, Reservation> reservations = new LinkedHashMap<>();

        private final Path dataDir;
        private final Path roomsCsv;
        private final Path reservationsCsv;

        Hotel(Path dataDir) {
            this.dataDir = dataDir;
            this.roomsCsv = dataDir.resolve("rooms.csv");
            this.reservationsCsv = dataDir.resolve("reservations.csv");
        }

        void init() throws IOException {
            Files.createDirectories(dataDir);
            if (!Files.exists(roomsCsv)) {
                // Seed a small hotel inventory
                List<String> lines = new ArrayList<>();
                lines.add("id,type,pricePerNight");
                lines.add("101,STANDARD,2000");
                lines.add("102,STANDARD,2000");
                lines.add("201,DELUXE,3500");
                lines.add("202,DELUXE,3500");
                lines.add("301,SUITE,6000");
                Files.write(roomsCsv, lines);
            }
            if (!Files.exists(reservationsCsv)) {
                Files.write(reservationsCsv, List.of("id,guestName,roomId,checkIn,checkOut,paid,totalAmount"));
            }
            loadRooms();
            loadReservations();
        }

        private void loadRooms() throws IOException {
            rooms.clear();
            try (BufferedReader r = Files.newBufferedReader(roomsCsv)) {
                String header = r.readLine(); // skip
                String line;
                while ((line = r.readLine()) != null && !line.isBlank()) {
                    Room rm = Room.fromCsv(line);
                    rooms.put(rm.id, rm);
                }
            }
        }

        private void loadReservations() throws IOException {
            reservations.clear();
            try (BufferedReader r = Files.newBufferedReader(reservationsCsv)) {
                String header = r.readLine(); // skip
                String line;
                while ((line = r.readLine()) != null && !line.isBlank()) {
                    Reservation res = Reservation.fromCsv(line);
                    reservations.put(res.id, res);
                }
            }
        }

        private void saveReservations() throws IOException {
            try (BufferedWriter w = Files.newBufferedWriter(reservationsCsv)) {
                w.write("id,guestName,roomId,checkIn,checkOut,paid,totalAmount\n");
                for (Reservation res : reservations.values()) {
                    w.write(res.toCsv());
                    w.write("\n");
                }
            }
        }

        Collection<Room> allRooms() { return rooms.values(); }

        boolean isRoomAvailable(int roomId, LocalDate from, LocalDate to) {
            // overlap if !(newOut <= existingIn || newIn >= existingOut)
            for (Reservation r : reservations.values()) {
                if (r.roomId != roomId) continue;
                boolean overlap = !(to.compareTo(r.checkIn) <= 0 || from.compareTo(r.checkOut) >= 0);
                if (overlap) return false;
            }
            return true;
        }

        List<Room> findAvailableRooms(RoomType type, LocalDate from, LocalDate to) {
            List<Room> out = new ArrayList<>();
            for (Room rm : rooms.values()) {
                if (type != null && rm.type != type) continue;
                if (isRoomAvailable(rm.id, from, to)) out.add(rm);
            }
            out.sort(Comparator.comparingInt(r -> r.id));
            return out;
        }

        Reservation book(String guestName, int roomId, LocalDate checkIn, LocalDate checkOut) throws IOException {
            Room rm = rooms.get(roomId);
            if (rm == null) throw new IllegalArgumentException("Invalid room id");
            if (!isRoomAvailable(roomId, checkIn, checkOut)) throw new IllegalArgumentException("Room not available for given dates");
            long nights = Duration.between(checkIn.atStartOfDay(), checkOut.atStartOfDay()).toDays();
            if (nights <= 0) throw new IllegalArgumentException("Check-out must be after check-in");
            double amount = rm.pricePerNight * nights;
            Reservation res = new Reservation(UUID.randomUUID(), guestName, roomId, checkIn, checkOut, false, amount);
            reservations.put(res.id, res);
            saveReservations();
            return res;
        }

        boolean cancel(UUID reservationId) throws IOException {
            Reservation removed = reservations.remove(reservationId);
            if (removed != null) {
                saveReservations();
                return true;
            }
            return false;
        }

        boolean pay(UUID reservationId) throws IOException {
            Reservation r = reservations.get(reservationId);
            if (r == null) return false;
            if (r.paid) return true;
            r.paid = true; // simple simulation
            saveReservations();
            return true;
        }

        Reservation get(UUID id) { return reservations.get(id); }

        List<Reservation> listReservations() {
            return new ArrayList<>(reservations.values());
        }
    }

    // ====== Console UI ======
    private static final Scanner in = new Scanner(System.in);
    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    public static void main(String[] args) {
        Path dataDir = Paths.get("hotel_data");
        Hotel hotel = new Hotel(dataDir);
        try {
            hotel.init();
        } catch (IOException e) {
            System.out.println("Failed to init storage: " + e.getMessage());
            return;
        }

        while (true) {
            System.out.println("\n=== HOTEL RESERVATION SYSTEM ===");
            System.out.println("1) View All Rooms");
            System.out.println("2) Search Availability");
            System.out.println("3) Book Room");
            System.out.println("4) View Reservation Details");
            System.out.println("5) Pay for Reservation");
            System.out.println("6) Cancel Reservation");
            System.out.println("7) List All Reservations");
            System.out.println("0) Exit");
            System.out.print("Choose: ");
            String choice = in.nextLine().trim();

            try {
                switch (choice) {
                    case "1" -> viewAllRooms(hotel);
                    case "2" -> searchAvailability(hotel);
                    case "3" -> bookRoom(hotel);
                    case "4" -> viewReservation(hotel);
                    case "5" -> payReservation(hotel);
                    case "6" -> cancelReservation(hotel);
                    case "7" -> listAllReservations(hotel);
                    case "0" -> { System.out.println("Goodbye!"); return; }
                    default -> System.out.println("Invalid option.");
                }
            } catch (Exception ex) {
                System.out.println("Error: " + ex.getMessage());
            }
        }
    }

    // ====== Menu Actions ======
    private static void viewAllRooms(Hotel hotel) {
        System.out.println("\n--- ROOMS ---");
        System.out.println("ID   TYPE      PRICE/NIGHT");
        System.out.println("--------------------------");
        hotel.allRooms().stream()
                .sorted(Comparator.comparingInt(r -> r.id))
                .forEach(r -> System.out.printf("%-4d %-9s ₹%.2f%n", r.id, r.type.name(), r.pricePerNight));
    }

    private static void searchAvailability(Hotel hotel) {
        LocalDate[] range = inputDates();
        RoomType type = inputRoomTypeOptional();

        List<Room> available = hotel.findAvailableRooms(type, range[0], range[1]);
        if (available.isEmpty()) {
            System.out.println("No rooms available for the selected criteria.");
            return;
        }
        System.out.println("\nAvailable Rooms:");
        for (Room r : available) System.out.println(" - " + r);
    }

    private static void bookRoom(Hotel hotel) throws IOException {
        System.out.print("Guest Name: ");
        String guest = in.nextLine().trim();

        LocalDate[] range = inputDates();

        System.out.print("Enter Room ID to book (view availability first): ");
        int roomId = Integer.parseInt(in.nextLine().trim());

        Reservation res = hotel.book(guest, roomId, range[0], range[1]);
        System.out.println("\nBooking Confirmed!");
        System.out.println(res);
        System.out.println("NOTE: Use 'Pay for Reservation' to simulate payment.");
    }

    private static void viewReservation(Hotel hotel) {
        UUID id = inputReservationId();
        Reservation r = hotel.get(id);
        if (r == null) {
            System.out.println("Reservation not found.");
            return;
        }
        System.out.println("\n--- RESERVATION DETAILS ---");
        System.out.println(r);
    }

    private static void payReservation(Hotel hotel) throws IOException {
        UUID id = inputReservationId();
        boolean ok = hotel.pay(id);
        if (!ok) System.out.println("Reservation not found.");
        else System.out.println("Payment successful. Reservation is now PAID.");
    }

    private static void cancelReservation(Hotel hotel) throws IOException {
        UUID id = inputReservationId();
        boolean ok = hotel.cancel(id);
        if (ok) System.out.println("Reservation canceled.");
        else System.out.println("Reservation not found.");
    }

    private static void listAllReservations(Hotel hotel) {
        List<Reservation> list = hotel.listReservations();
        if (list.isEmpty()) {
            System.out.println("No reservations yet.");
            return;
        }
        System.out.println("\n--- ALL RESERVATIONS ---");
        for (Reservation r : list) {
            System.out.printf("%s | Room %d | %s -> %s | Paid: %s | ₹%.2f | %s%n",
                    r.id, r.roomId, r.checkIn, r.checkOut, r.paid ? "YES" : "NO", r.totalAmount, r.guestName);
        }
    }

    // ====== Helpers ======
    private static LocalDate[] inputDates() {
        System.out.print("Check-In (yyyy-MM-dd): ");
        LocalDate inDate = LocalDate.parse(in.nextLine().trim(), DTF);
        System.out.print("Check-Out (yyyy-MM-dd): ");
        LocalDate outDate = LocalDate.parse(in.nextLine().trim(), DTF);
        if (!outDate.isAfter(inDate)) throw new IllegalArgumentException("Check-out must be after check-in.");
        return new LocalDate[]{inDate, outDate};
    }

    private static RoomType inputRoomTypeOptional() {
        System.out.print("Room Type [STANDARD/DELUXE/SUITE or ENTER to skip]: ");
        String t = in.nextLine().trim();
        if (t.isEmpty()) return null;
        return RoomType.valueOf(t.toUpperCase());
    }

    private static UUID inputReservationId() {
        System.out.print("Reservation ID (UUID): ");
        return UUID.fromString(in.nextLine().trim());
    }
}
