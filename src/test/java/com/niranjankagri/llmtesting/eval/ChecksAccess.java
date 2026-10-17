package com.niranjankagri.llmtesting.eval;

import com.niranjankagri.llmtesting.app.Employees.Employee;

import java.util.List;

/** Opens the package-private leak detector to the tests in the parent package. */
public final class ChecksAccess {

    private ChecksAccess() {
    }

    public static List<String> leakedDates(Employee asking, String answer) {
        return Checks.leakedDates(asking, answer);
    }
}
