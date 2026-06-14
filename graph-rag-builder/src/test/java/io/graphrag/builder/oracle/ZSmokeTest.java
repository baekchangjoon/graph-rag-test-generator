package io.graphrag.builder.oracle;

import com.microsoft.z3.Context;
import com.microsoft.z3.IntExpr;
import com.microsoft.z3.Model;
import com.microsoft.z3.Solver;
import com.microsoft.z3.Status;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ZSmokeTest {

    @Test
    void z3_native_loads_and_solves() {
        try (Context ctx = new Context()) {
            IntExpr x = ctx.mkIntConst("x");
            Solver s = ctx.mkSolver();
            s.add(ctx.mkGt(x, ctx.mkInt(5)));
            s.add(ctx.mkLt(x, ctx.mkInt(7)));
            assertThat(s.check()).isEqualTo(Status.SATISFIABLE);
            Model m = s.getModel();
            assertThat(m.evaluate(x, false).toString()).isEqualTo("6");
        }
    }
}
