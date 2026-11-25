package io.formkiln.server.forms;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SubmissionExportTest {

    @Test
    void neutralizesFormulas() {
        assertThat(SubmissionExport.neutralize("=1+1")).isEqualTo("'=1+1");
        assertThat(SubmissionExport.neutralize("+44 20")).isEqualTo("'+44 20");
        assertThat(SubmissionExport.neutralize("-x")).isEqualTo("'-x");
        assertThat(SubmissionExport.neutralize("@cmd")).isEqualTo("'@cmd");
        assertThat(SubmissionExport.neutralize("\tx")).isEqualTo("'\tx");
        assertThat(SubmissionExport.neutralize("Ada")).isEqualTo("Ada");
        assertThat(SubmissionExport.neutralize("")).isEmpty();
    }

    @Test
    void quotesOnlyWhenNeeded() {
        assertThat(SubmissionExport.quote("plain")).isEqualTo("plain");
        assertThat(SubmissionExport.quote("a,b")).isEqualTo("\"a,b\"");
        assertThat(SubmissionExport.quote("say \"hi\"")).isEqualTo("\"say \"\"hi\"\"\"");
        assertThat(SubmissionExport.quote("two\nlines")).isEqualTo("\"two\nlines\"");
    }

    @Test
    void formatsNumbersWithoutExponentOrTrailingZeros() {
        assertThat(SubmissionExport.number(1200.0)).isEqualTo("1200");
        assertThat(SubmissionExport.number(-5.0)).isEqualTo("-5");
        assertThat(SubmissionExport.number(0.1)).isEqualTo("0.1");
        assertThat(SubmissionExport.number(1e20)).isEqualTo("100000000000000000000");
    }
}
