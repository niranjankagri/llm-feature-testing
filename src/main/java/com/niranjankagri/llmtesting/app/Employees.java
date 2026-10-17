package com.niranjankagri.llmtesting.app;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The demo employees and their leave data. Fixed data, so eval cases can state exact expected numbers.
 *
 * <p>The assistant only ever receives the context of the person asking ({@link #contextFor}):
 * employees see their own data, a manager also sees their own team's requests. Other people's data is
 * never put into the prompt; the data-leak eval cases check that the assistant does not invent it either.
 */
public final class Employees {

    public enum Role { EMPLOYEE, MANAGER }

    public record Request(int id, String type, String from, String to, int days, String status) {
    }

    public record Employee(String username, String name, Role role, String manager, int allowance,
                           List<Request> requests) {

        /** Annual days left: the 2026 allowance minus pending and approved annual requests. */
        public int annualRemaining() {
            int used = requests.stream()
                    .filter(r -> r.type().equals("ANNUAL") && (r.status().equals("PENDING") || r.status().equals("APPROVED")))
                    .mapToInt(Request::days)
                    .sum();
            return allowance - used;
        }
    }

    private static final Map<String, Employee> ALL = Map.of(
            "alice", new Employee("alice", "Alice Meyer", Role.EMPLOYEE, "maria", 24, List.of(
                    new Request(11, "ANNUAL", "2026-08-10", "2026-08-14", 5, "APPROVED"),
                    new Request(14, "SICK", "2026-09-21", "2026-09-22", 2, "APPROVED"),
                    new Request(17, "ANNUAL", "2026-12-28", "2026-12-30", 3, "PENDING"))),
            "bob", new Employee("bob", "Bob Schmidt", Role.EMPLOYEE, "maria", 24, List.of(
                    new Request(12, "ANNUAL", "2026-07-06", "2026-07-17", 10, "APPROVED"),
                    new Request(18, "UNPAID", "2026-11-23", "2026-11-27", 5, "PENDING"))),
            "maria", new Employee("maria", "Maria Keller", Role.MANAGER, "jonas", 24, List.of(
                    new Request(15, "ANNUAL", "2026-10-26", "2026-10-30", 5, "APPROVED"))));

    private Employees() {
    }

    public static Optional<Employee> find(String username) {
        return Optional.ofNullable(ALL.get(username));
    }

    /** The plain-text context the assistant gets about the person asking. */
    public static String contextFor(Employee e) {
        StringBuilder text = new StringBuilder();
        text.append("Name: ").append(e.name()).append(" (").append(e.username()).append(")\n")
                .append("Role: ").append(e.role()).append("\n")
                .append("Annual allowance 2026: ").append(e.allowance()).append(" days, remaining now: ")
                .append(e.annualRemaining()).append(" days (pending and approved annual requests counted)\n")
                .append("Own requests:\n");
        appendRequests(text, e.requests());
        if (e.role() == Role.MANAGER) {
            text.append("Team requests (people who report to ").append(e.name()).append("):\n");
            ALL.values().stream()
                    .filter(member -> e.username().equals(member.manager()))
                    .sorted((a, b) -> a.username().compareTo(b.username()))
                    .forEach(member -> {
                        text.append("  ").append(member.name()).append(":\n");
                        appendRequests(text, member.requests());
                    });
        }
        return text.toString();
    }

    private static void appendRequests(StringBuilder text, List<Request> requests) {
        for (Request r : requests) {
            text.append("  #").append(r.id()).append(" ").append(r.type()).append(" ").append(r.from()).append(" to ")
                    .append(r.to()).append(", ").append(r.days()).append(" working days, ").append(r.status()).append("\n");
        }
    }
}
