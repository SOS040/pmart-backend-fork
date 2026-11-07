package com.pmart.mfc.location.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.pmart.mfc.location.domain.LocationRules.State;
import com.pmart.mfc.location.domain.LocationRules.Transition;
import org.junit.jupiter.api.Test;

class LocationRulesTest {

  @Test
  void transitionsGoThroughActiveAndRetiredIsTerminal() {
    assertThat(LocationRules.change(State.ACTIVE, State.CLOSED)).isEqualTo(Transition.APPLY);
    assertThat(LocationRules.change(State.CLOSED, State.ACTIVE)).isEqualTo(Transition.APPLY);
    assertThat(LocationRules.change(State.ACTIVE, State.COUNT_LOCKED)).isEqualTo(Transition.APPLY);
    assertThat(LocationRules.change(State.COUNT_LOCKED, State.ACTIVE)).isEqualTo(Transition.APPLY);
    assertThat(LocationRules.change(State.CLOSED, State.COUNT_LOCKED))
        .isEqualTo(Transition.INVALID);
    assertThat(LocationRules.change(State.ACTIVE, State.RETIRED)).isEqualTo(Transition.INVALID);
    assertThat(LocationRules.change(State.RETIRED, State.ACTIVE)).isEqualTo(Transition.INVALID);
    assertThat(LocationRules.change(State.CLOSED, State.CLOSED)).isEqualTo(Transition.ALREADY);
  }
}
