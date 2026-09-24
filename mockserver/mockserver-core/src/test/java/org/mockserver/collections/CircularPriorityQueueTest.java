package org.mockserver.collections;

import org.junit.Test;
import org.mockserver.mock.Expectation;
import org.mockserver.mock.SortableExpectationId;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.hamcrest.core.Is.is;
import static org.mockserver.mock.Expectation.when;
import static org.mockserver.mock.SortableExpectationId.EXPECTATION_SORTABLE_PRIORITY_COMPARATOR;
import static org.mockserver.model.HttpRequest.request;

public class CircularPriorityQueueTest {

    @Test
    public void shouldNotAllowAddingMoreThenMaximumNumberOfEntriesWhenUsingAdd() {
        // given
        CircularPriorityQueue<String, SortableExpectationId, SortableExpectationId> concurrentLinkedQueue = new CircularPriorityQueue<>(
            3,
            EXPECTATION_SORTABLE_PRIORITY_COMPARATOR,
            sortableExpectationId -> sortableExpectationId,
            sortableExpectationId -> sortableExpectationId.id
        );

        // when
        concurrentLinkedQueue.add(new SortableExpectationId("1", 0, 0));
        concurrentLinkedQueue.add(new SortableExpectationId("2", 0, 0));
        concurrentLinkedQueue.add(new SortableExpectationId("3", 0, 0));
        concurrentLinkedQueue.add(new SortableExpectationId("4", 0, 0));

        // then
        assertThat(concurrentLinkedQueue.size(), is(3));
        List<SortableExpectationId> actual = concurrentLinkedQueue.toSortedList();
        assertThat(actual, not(contains(new SortableExpectationId("1", 0, 0))));
        assertThat(actual, containsInAnyOrder(
            new SortableExpectationId("2", 0, 0),
            new SortableExpectationId("3", 0, 0),
            new SortableExpectationId("4", 0, 0)
        ));
    }

    @Test
    public void shouldSortOrder() {
        // given
        CircularPriorityQueue<String, SortableExpectationId, SortableExpectationId> concurrentLinkedQueue = new CircularPriorityQueue<>(
            3,
            EXPECTATION_SORTABLE_PRIORITY_COMPARATOR,
            sortableExpectationId -> sortableExpectationId,
            sortableExpectationId -> sortableExpectationId.id
        );

        // when
        concurrentLinkedQueue.add(new SortableExpectationId("4", 0, 0));
        assertThat(concurrentLinkedQueue.toSortedList().toArray(new SortableExpectationId[0]), is(new SortableExpectationId[]{
            new SortableExpectationId("4", 0, 0)
        }));
        concurrentLinkedQueue.add(new SortableExpectationId("1", 0, 0));
        assertThat(concurrentLinkedQueue.toSortedList().toArray(new SortableExpectationId[0]), is(new SortableExpectationId[]{
            new SortableExpectationId("1", 0, 0),
            new SortableExpectationId("4", 0, 0)
        }));
        concurrentLinkedQueue.add(new SortableExpectationId("3", 0, 0));
        assertThat(concurrentLinkedQueue.toSortedList().toArray(new SortableExpectationId[0]), is(new SortableExpectationId[]{
            new SortableExpectationId("1", 0, 0),
            new SortableExpectationId("3", 0, 0),
            new SortableExpectationId("4", 0, 0)
        }));
        concurrentLinkedQueue.add(new SortableExpectationId("2", 0, 0));
        assertThat(concurrentLinkedQueue.toSortedList().toArray(new SortableExpectationId[0]), is(new SortableExpectationId[]{
            new SortableExpectationId("1", 0, 0),
            new SortableExpectationId("2", 0, 0),
            new SortableExpectationId("3", 0, 0)
        }));
        concurrentLinkedQueue.add(new SortableExpectationId("5", 0, 0));
        assertThat(concurrentLinkedQueue.toSortedList().toArray(new SortableExpectationId[0]), is(new SortableExpectationId[]{
            new SortableExpectationId("2", 0, 0),
            new SortableExpectationId("3", 0, 0),
            new SortableExpectationId("5", 0, 0)
        }));

        // then
        assertThat(concurrentLinkedQueue.size(), is(3));
        List<SortableExpectationId> actual = concurrentLinkedQueue.toSortedList();
        assertThat(actual, not(contains(new SortableExpectationId("1", 0, 0))));
        assertThat(actual, not(contains(new SortableExpectationId("2", 0, 0))));
        assertThat(actual, contains(
            new SortableExpectationId("2", 0, 0),
            new SortableExpectationId("3", 0, 0),
            new SortableExpectationId("5", 0, 0)
        ));
    }

    @Test
    public void shouldSortOrderAndRemove() {
        // given
        CircularPriorityQueue<String, SortableExpectationId, SortableExpectationId> concurrentLinkedQueue = new CircularPriorityQueue<>(
            3,
            EXPECTATION_SORTABLE_PRIORITY_COMPARATOR,
            sortableExpectationId -> sortableExpectationId,
            sortableExpectationId -> sortableExpectationId.id
        );

        // when
        concurrentLinkedQueue.add(new SortableExpectationId("4", 0, 0));
        assertThat(concurrentLinkedQueue.toSortedList().toArray(new SortableExpectationId[0]), is(new SortableExpectationId[]{
            new SortableExpectationId("4", 0, 0)
        }));
        concurrentLinkedQueue.add(new SortableExpectationId("1", 0, 0));
        assertThat(concurrentLinkedQueue.toSortedList().toArray(new SortableExpectationId[0]), is(new SortableExpectationId[]{
            new SortableExpectationId("1", 0, 0),
            new SortableExpectationId("4", 0, 0)
        }));
        concurrentLinkedQueue.remove(new SortableExpectationId("4", 0, 0)); // remove last
        assertThat(concurrentLinkedQueue.toSortedList().toArray(new SortableExpectationId[0]), is(new SortableExpectationId[]{
            new SortableExpectationId("1", 0, 0)
        }));
        concurrentLinkedQueue.add(new SortableExpectationId("3", 0, 0));
        assertThat(concurrentLinkedQueue.toSortedList().toArray(new SortableExpectationId[0]), is(new SortableExpectationId[]{
            new SortableExpectationId("1", 0, 0),
            new SortableExpectationId("3", 0, 0)
        }));
        concurrentLinkedQueue.add(new SortableExpectationId("2", 0, 0));
        assertThat(concurrentLinkedQueue.toSortedList().toArray(new SortableExpectationId[0]), is(new SortableExpectationId[]{
            new SortableExpectationId("1", 0, 0),
            new SortableExpectationId("2", 0, 0),
            new SortableExpectationId("3", 0, 0)
        }));
        concurrentLinkedQueue.remove(new SortableExpectationId("2", 0, 0)); // remove middle
        assertThat(concurrentLinkedQueue.toSortedList().toArray(new SortableExpectationId[0]), is(new SortableExpectationId[]{
            new SortableExpectationId("1", 0, 0),
            new SortableExpectationId("3", 0, 0)
        }));
        concurrentLinkedQueue.add(new SortableExpectationId("5", 0, 0));
        assertThat(concurrentLinkedQueue.toSortedList().toArray(new SortableExpectationId[0]), is(new SortableExpectationId[]{
            new SortableExpectationId("1", 0, 0),
            new SortableExpectationId("3", 0, 0),
            new SortableExpectationId("5", 0, 0)
        }));
        concurrentLinkedQueue.remove(new SortableExpectationId("1", 0, 0)); // remove first
        assertThat(concurrentLinkedQueue.toSortedList().toArray(new SortableExpectationId[0]), is(new SortableExpectationId[]{
            new SortableExpectationId("3", 0, 0),
            new SortableExpectationId("5", 0, 0)
        }));

        // then
        assertThat(concurrentLinkedQueue.size(), is(2));
        List<SortableExpectationId> actual = concurrentLinkedQueue.toSortedList();
        assertThat(actual, not(contains(new SortableExpectationId("1", 0, 0))));
        assertThat(actual, not(contains(new SortableExpectationId("2", 0, 0))));
        assertThat(actual, not(contains(new SortableExpectationId("3", 0, 0))));
        assertThat(actual, not(contains(new SortableExpectationId("4", 0, 0))));
        assertThat(actual, contains(
            new SortableExpectationId("3", 0, 0),
            new SortableExpectationId("5", 0, 0)
        ));
    }

    @Test
    public void shouldSortExpectationOrderSamePriorityInsertedInOrder() {
        // given
        CircularPriorityQueue<String, Expectation, SortableExpectationId> concurrentLinkedQueue = new CircularPriorityQueue<>(3, EXPECTATION_SORTABLE_PRIORITY_COMPARATOR, Expectation::getSortableId, Expectation::getId);

        long currentTimeMillis = System.currentTimeMillis();
        Expectation one = when(request("one"), 0).withCreated(currentTimeMillis + 1);
        Expectation two = when(request("two"), 0).withCreated(currentTimeMillis + 2);
        Expectation three = when(request("three"), 0).withCreated(currentTimeMillis + 3);
        Expectation four = when(request("four"), 0).withCreated(currentTimeMillis + 4);
        Expectation five = when(request("five"), 0).withCreated(currentTimeMillis + 5);

        // when
        concurrentLinkedQueue.add(one);
        assertThat(concurrentLinkedQueue.toSortedList().toArray(new Expectation[0]), is(new Expectation[]{
            one
        }));
        concurrentLinkedQueue.add(two);
        assertThat(concurrentLinkedQueue.toSortedList().toArray(new Expectation[0]), is(new Expectation[]{
            one,
            two
        }));
        concurrentLinkedQueue.add(three);
        assertThat(concurrentLinkedQueue.toSortedList().toArray(new Expectation[0]), is(new Expectation[]{
            one,
            two,
            three
        }));
        concurrentLinkedQueue.add(four);
        assertThat(concurrentLinkedQueue.toSortedList().toArray(new Expectation[0]), is(new Expectation[]{
            two,
            three,
            four
        }));
        concurrentLinkedQueue.add(five);
        assertThat(concurrentLinkedQueue.toSortedList().toArray(new Expectation[0]), is(new Expectation[]{
            three,
            four,
            five
        }));

        // then
        assertThat(concurrentLinkedQueue.size(), is(3));
        assertThat(concurrentLinkedQueue.toSortedList(), not(contains(one)));
        assertThat(concurrentLinkedQueue.toSortedList(), not(contains(two)));
        assertThat(concurrentLinkedQueue.toSortedList(), contains(three, four, five));
    }

    @Test
    public void shouldSortExpectationOrderSamePriorityInsertedOutOfOrder() {
        // given
        CircularPriorityQueue<String, Expectation, SortableExpectationId> concurrentLinkedQueue = new CircularPriorityQueue<>(3, EXPECTATION_SORTABLE_PRIORITY_COMPARATOR, Expectation::getSortableId, Expectation::getId);

        long currentTimeMillis = System.currentTimeMillis();
        Expectation one = when(request("one"), 0).withCreated(currentTimeMillis + 1);
        Expectation two = when(request("two"), 0).withCreated(currentTimeMillis + 2);
        Expectation three = when(request("three"), 0).withCreated(currentTimeMillis + 3);
        Expectation four = when(request("four"), 0).withCreated(currentTimeMillis + 4);
        Expectation five = when(request("five"), 0).withCreated(currentTimeMillis + 5);

        // when
        concurrentLinkedQueue.add(two);
        assertThat(concurrentLinkedQueue.toSortedList().toArray(new Expectation[0]), is(new Expectation[]{
            two
        }));
        concurrentLinkedQueue.add(three);
        assertThat(concurrentLinkedQueue.toSortedList().toArray(new Expectation[0]), is(new Expectation[]{
            two,
            three
        }));
        concurrentLinkedQueue.add(one);
        assertThat(concurrentLinkedQueue.toSortedList().toArray(new Expectation[0]), is(new Expectation[]{
            one,
            two,
            three
        }));
        concurrentLinkedQueue.add(five);
        assertThat(concurrentLinkedQueue.toSortedList().toArray(new Expectation[0]), is(new Expectation[]{
            one,
            three,
            five
        }));
        concurrentLinkedQueue.add(four);
        assertThat(concurrentLinkedQueue.toSortedList().toArray(new Expectation[0]), is(new Expectation[]{
            one,
            four,
            five
        }));

        // then
        assertThat(concurrentLinkedQueue.size(), is(3));
        assertThat(concurrentLinkedQueue.toSortedList(), not(contains(one)));
        assertThat(concurrentLinkedQueue.toSortedList(), not(contains(two)));
        assertThat(concurrentLinkedQueue.toSortedList(), contains(one, four, five));
        assertThat(concurrentLinkedQueue.getByKey(five.getId()), is(Optional.of(five)));
        assertThat(concurrentLinkedQueue.getByKey(four.getId()), is(Optional.of(four)));
        assertThat(concurrentLinkedQueue.getByKey(three.getId()), is(Optional.empty()));
        assertThat(concurrentLinkedQueue.getByKey(two.getId()), is(Optional.empty()));
        assertThat(concurrentLinkedQueue.getByKey(one.getId()), is(Optional.of(one)));
    }

    @Test
    public void shouldSortExpectationOrderDifferentIdsButConsistentWithTimeInsertedInOrderAndPriority() {
        // given
        CircularPriorityQueue<String, Expectation, SortableExpectationId> concurrentLinkedQueue = new CircularPriorityQueue<>(3, EXPECTATION_SORTABLE_PRIORITY_COMPARATOR, Expectation::getSortableId, Expectation::getId);

        long currentTimeMillis = System.currentTimeMillis();
        Expectation one = when(request("one"), 0).withCreated(currentTimeMillis).withId("4");
        Expectation two = when(request("two"), 0).withCreated(currentTimeMillis).withId("3");
        Expectation three = when(request("three"), 0).withCreated(currentTimeMillis).withId("2");
        Expectation four = when(request("four"), 0).withCreated(currentTimeMillis).withId("1");
        Expectation five = when(request("five"), 0).withCreated(currentTimeMillis).withId("4");

        // when
        concurrentLinkedQueue.add(one);
        assertThat(concurrentLinkedQueue.toSortedList().toArray(new Expectation[0]), is(new Expectation[]{
            one
        }));
        concurrentLinkedQueue.add(two);
        assertThat(concurrentLinkedQueue.toSortedList().toArray(new Expectation[0]), is(new Expectation[]{
            two,
            one
        }));
        concurrentLinkedQueue.add(three);
        assertThat(concurrentLinkedQueue.toSortedList().toArray(new Expectation[0]), is(new Expectation[]{
            three,
            two,
            one
        }));
        concurrentLinkedQueue.add(four);
        assertThat(concurrentLinkedQueue.toSortedList().toArray(new Expectation[0]), is(new Expectation[]{
            four,
            three,
            two
        }));
        concurrentLinkedQueue.add(five);
        assertThat(concurrentLinkedQueue.toSortedList().toArray(new Expectation[0]), is(new Expectation[]{
            four,
            three,
            five
        }));

        // then
        assertThat(concurrentLinkedQueue.size(), is(3));
        assertThat(concurrentLinkedQueue.toSortedList(), not(contains(one)));
        assertThat(concurrentLinkedQueue.toSortedList(), not(contains(two)));
        assertThat(concurrentLinkedQueue.toSortedList(), contains(four, three, five));
        assertThat(concurrentLinkedQueue.getByKey(five.getId()), is(Optional.of(five)));
        assertThat(concurrentLinkedQueue.getByKey(four.getId()), is(Optional.of(four)));
        assertThat(concurrentLinkedQueue.getByKey(three.getId()), is(Optional.of(three)));
        assertThat(concurrentLinkedQueue.getByKey(two.getId()), is(Optional.empty()));
    }

    @Test
    public void shouldSortExpectationOrderDifferentPriorityButConsistentWithTimeInsertedInOrder() {
        // given
        CircularPriorityQueue<String, Expectation, SortableExpectationId> concurrentLinkedQueue = new CircularPriorityQueue<>(3, EXPECTATION_SORTABLE_PRIORITY_COMPARATOR, Expectation::getSortableId, Expectation::getId);

        long currentTimeMillis = System.currentTimeMillis();
        Expectation one = when(request("one"), 4).withCreated(currentTimeMillis + 1);
        Expectation two = when(request("two"), 3).withCreated(currentTimeMillis + 2);
        Expectation three = when(request("three"), 2).withCreated(currentTimeMillis + 3);
        Expectation four = when(request("four"), 1).withCreated(currentTimeMillis + 4);
        Expectation five = when(request("five"), 0).withCreated(currentTimeMillis + 5);

        // when
        concurrentLinkedQueue.add(one);
        assertThat(concurrentLinkedQueue.toSortedList().toArray(new Expectation[0]), is(new Expectation[]{
            one
        }));
        concurrentLinkedQueue.add(two);
        assertThat(concurrentLinkedQueue.toSortedList().toArray(new Expectation[0]), is(new Expectation[]{
            one,
            two
        }));
        concurrentLinkedQueue.add(three);
        assertThat(concurrentLinkedQueue.toSortedList().toArray(new Expectation[0]), is(new Expectation[]{
            one,
            two,
            three
        }));
        concurrentLinkedQueue.add(four);
        assertThat(concurrentLinkedQueue.toSortedList().toArray(new Expectation[0]), is(new Expectation[]{
            two,
            three,
            four
        }));
        concurrentLinkedQueue.add(five);
        assertThat(concurrentLinkedQueue.toSortedList().toArray(new Expectation[0]), is(new Expectation[]{
            three,
            four,
            five
        }));

        // then
        assertThat(concurrentLinkedQueue.size(), is(3));
        assertThat(concurrentLinkedQueue.toSortedList(), not(contains(one)));
        assertThat(concurrentLinkedQueue.toSortedList(), not(contains(two)));
        assertThat(concurrentLinkedQueue.toSortedList(), contains(three, four, five));
        assertThat(concurrentLinkedQueue.getByKey(five.getId()), is(Optional.of(five)));
        assertThat(concurrentLinkedQueue.getByKey(four.getId()), is(Optional.of(four)));
        assertThat(concurrentLinkedQueue.getByKey(three.getId()), is(Optional.of(three)));
        assertThat(concurrentLinkedQueue.getByKey(two.getId()), is(Optional.empty()));
        assertThat(concurrentLinkedQueue.getByKey(one.getId()), is(Optional.empty()));
    }

    @Test
    public void shouldSortExpectationOrderDifferentPriorityButConsistentWithTimeInsertedOutOfOrder() {
        // given
        CircularPriorityQueue<String, Expectation, SortableExpectationId> concurrentLinkedQueue = new CircularPriorityQueue<>(3, EXPECTATION_SORTABLE_PRIORITY_COMPARATOR, Expectation::getSortableId, Expectation::getId);

        long currentTimeMillis = System.currentTimeMillis();
        Expectation one = when(request("one"), 4).withCreated(currentTimeMillis + 1);
        Expectation two = when(request("two"), 3).withCreated(currentTimeMillis + 2);
        Expectation three = when(request("three"), 2).withCreated(currentTimeMillis + 3);
        Expectation four = when(request("four"), 1).withCreated(currentTimeMillis + 4);
        Expectation five = when(request("five"), 0).withCreated(currentTimeMillis + 5);

        // when
        concurrentLinkedQueue.add(two);
        assertThat(concurrentLinkedQueue.toSortedList().toArray(new Expectation[0]), is(new Expectation[]{
            two
        }));
        concurrentLinkedQueue.add(three);
        assertThat(concurrentLinkedQueue.toSortedList().toArray(new Expectation[0]), is(new Expectation[]{
            two,
            three
        }));
        concurrentLinkedQueue.add(one);
        assertThat(concurrentLinkedQueue.toSortedList().toArray(new Expectation[0]), is(new Expectation[]{
            one,
            two,
            three
        }));
        concurrentLinkedQueue.add(five);
        assertThat(concurrentLinkedQueue.toSortedList().toArray(new Expectation[0]), is(new Expectation[]{
            one,
            three,
            five
        }));
        concurrentLinkedQueue.add(four);
        assertThat(concurrentLinkedQueue.toSortedList().toArray(new Expectation[0]), is(new Expectation[]{
            one,
            four,
            five
        }));

        // then
        assertThat(concurrentLinkedQueue.size(), is(3));
        assertThat(concurrentLinkedQueue.toSortedList(), not(contains(two)));
        assertThat(concurrentLinkedQueue.toSortedList(), not(contains(three)));
        assertThat(concurrentLinkedQueue.toSortedList(), contains(one, four, five));
        assertThat(concurrentLinkedQueue.getByKey(five.getId()), is(Optional.of(five)));
        assertThat(concurrentLinkedQueue.getByKey(four.getId()), is(Optional.of(four)));
        assertThat(concurrentLinkedQueue.getByKey(three.getId()), is(Optional.empty()));
        assertThat(concurrentLinkedQueue.getByKey(two.getId()), is(Optional.empty()));
        assertThat(concurrentLinkedQueue.getByKey(one.getId()), is(Optional.of(one)));
    }

    @Test
    public void shouldSortExpectationOrderDifferentPriorityInconsistentWithTimeInsertedOutOfOrder() {
        // given
        CircularPriorityQueue<String, Expectation, SortableExpectationId> concurrentLinkedQueue = new CircularPriorityQueue<>(3, EXPECTATION_SORTABLE_PRIORITY_COMPARATOR, Expectation::getSortableId, Expectation::getId);

        long currentTimeMillis = 0;
        Expectation one = when(request("one"), 0).withCreated(currentTimeMillis + 1);
        Expectation two = when(request("two"), 0).withCreated(currentTimeMillis + 2);
        Expectation three = when(request("three"), 2).withCreated(currentTimeMillis + 3);
        Expectation four = when(request("four"), 1).withCreated(currentTimeMillis + 4);
        Expectation five = when(request("five"), 3).withCreated(currentTimeMillis + 5);

        // when
        concurrentLinkedQueue.add(one);
        assertThat(concurrentLinkedQueue.toSortedList().toArray(new Expectation[0]), is(new Expectation[]{
            one,
        }));
        concurrentLinkedQueue.add(two);
        assertThat(concurrentLinkedQueue.toSortedList().toArray(new Expectation[0]), is(new Expectation[]{
            one,
            two,
        }));
        concurrentLinkedQueue.add(three);
        assertThat(concurrentLinkedQueue.toSortedList().toArray(new Expectation[0]), is(new Expectation[]{
            three,
            one,
            two,
        }));
        concurrentLinkedQueue.add(four);
        assertThat(concurrentLinkedQueue.toSortedList().toArray(new Expectation[0]), is(new Expectation[]{
            three,
            four,
            two,
        }));
        concurrentLinkedQueue.add(five);
        assertThat(concurrentLinkedQueue.toSortedList().toArray(new Expectation[0]), is(new Expectation[]{
            five,
            three,
            four,
        }));

        // then
        assertThat(concurrentLinkedQueue.size(), is(3));
        assertThat(concurrentLinkedQueue.toSortedList(), not(contains(one)));
        assertThat(concurrentLinkedQueue.toSortedList(), not(contains(two)));
        assertThat(concurrentLinkedQueue.toSortedList(), contains(five, three, four));
        assertThat(concurrentLinkedQueue.getByKey(five.getId()), is(Optional.of(five)));
        assertThat(concurrentLinkedQueue.getByKey(four.getId()), is(Optional.of(four)));
        assertThat(concurrentLinkedQueue.getByKey(three.getId()), is(Optional.of(three)));
        assertThat(concurrentLinkedQueue.getByKey(two.getId()), is(Optional.empty()));
        assertThat(concurrentLinkedQueue.getByKey(one.getId()), is(Optional.empty()));
    }

    @Test
    public void shouldSortExpectationOrderDifferentPriorityGroupsLargerQueue() {
        // given
        CircularPriorityQueue<String, Expectation, SortableExpectationId> concurrentLinkedQueue = new CircularPriorityQueue<>(5, EXPECTATION_SORTABLE_PRIORITY_COMPARATOR, Expectation::getSortableId, Expectation::getId);

        long currentTimeMillis = 0;
        Expectation one = when(request("one"), 1).withCreated(currentTimeMillis);
        Expectation two = when(request("two"), 1).withCreated(currentTimeMillis + 1);
        Expectation three = when(request("three"), 2).withCreated(currentTimeMillis);
        Expectation four = when(request("four"), 3).withCreated(currentTimeMillis);
        Expectation five = when(request("five"), 3).withCreated(currentTimeMillis + 1);

        // when
        concurrentLinkedQueue.add(two);
        assertThat(concurrentLinkedQueue.toSortedList().toArray(new Expectation[0]), is(new Expectation[]{
            two,
        }));
        concurrentLinkedQueue.add(one);
        assertThat(concurrentLinkedQueue.toSortedList().toArray(new Expectation[0]), is(new Expectation[]{
            one,
            two,
        }));
        concurrentLinkedQueue.add(three);
        assertThat(concurrentLinkedQueue.toSortedList().toArray(new Expectation[0]), is(new Expectation[]{
            three,
            one,
            two,
        }));
        concurrentLinkedQueue.add(five);
        assertThat(concurrentLinkedQueue.toSortedList().toArray(new Expectation[0]), is(new Expectation[]{
            five,
            three,
            one,
            two,
        }));
        concurrentLinkedQueue.add(four);
        assertThat(concurrentLinkedQueue.toSortedList().toArray(new Expectation[0]), is(new Expectation[]{
            four,
            five,
            three,
            one,
            two,
        }));

        // then
        assertThat(concurrentLinkedQueue.size(), is(5));
        assertThat(concurrentLinkedQueue.toSortedList(), contains(four, five, three, one, two));
        assertThat(concurrentLinkedQueue.getByKey(five.getId()), is(Optional.of(five)));
        assertThat(concurrentLinkedQueue.getByKey(four.getId()), is(Optional.of(four)));
        assertThat(concurrentLinkedQueue.getByKey(three.getId()), is(Optional.of(three)));
        assertThat(concurrentLinkedQueue.getByKey(two.getId()), is(Optional.of(two)));
        assertThat(concurrentLinkedQueue.getByKey(one.getId()), is(Optional.of(one)));
    }

    @Test
    public void shouldReturnCachedListBetweenMutationsAndInvalidateCacheOnMutation() {
        // given
        CircularPriorityQueue<String, Expectation, SortableExpectationId> queue =
            new CircularPriorityQueue<>(5, EXPECTATION_SORTABLE_PRIORITY_COMPARATOR, Expectation::getSortableId, Expectation::getId);
        long ts = System.currentTimeMillis();
        Expectation a = when(request("a"), 0).withCreated(ts);
        Expectation b = when(request("b"), 0).withCreated(ts + 1);
        queue.add(a);
        queue.add(b);

        // same list instance is returned on repeated reads without mutation
        List<Expectation> first = queue.toSortedList();
        List<Expectation> second = queue.toSortedList();
        assertThat("cache should return same instance on repeated reads", first == second, is(true));

        // a mutation invalidates the cache — a new list is produced
        Expectation c = when(request("c"), 1).withCreated(ts + 2);
        queue.add(c);
        List<Expectation> afterMutation = queue.toSortedList();
        assertThat("cache should be invalidated after add", first == afterMutation, is(false));
        assertThat(afterMutation, contains(c, a, b));

        // remove also invalidates
        queue.remove(c);
        List<Expectation> afterRemove = queue.toSortedList();
        assertThat("cache should be invalidated after remove", afterMutation == afterRemove, is(false));
        assertThat(afterRemove, contains(a, b));
    }

    @Test
    public void shouldRemove() {
        // given - a queue
        CircularPriorityQueue<String, Expectation, SortableExpectationId> concurrentLinkedQueue = new CircularPriorityQueue<>(5, EXPECTATION_SORTABLE_PRIORITY_COMPARATOR, Expectation::getSortableId, Expectation::getId);

        long currentTimeMillis = 0;
        Expectation one = when(request("one"), 1).withCreated(currentTimeMillis);
        Expectation two = when(request("two"), 1).withCreated(currentTimeMillis + 1);
        Expectation three = when(request("three"), 2).withCreated(currentTimeMillis);
        Expectation four = when(request("four"), 3).withCreated(currentTimeMillis);
        Expectation five = when(request("five"), 3).withCreated(currentTimeMillis + 1);

        // given - added items
        concurrentLinkedQueue.add(two);
        concurrentLinkedQueue.add(one);
        concurrentLinkedQueue.add(three);
        concurrentLinkedQueue.add(five);
        concurrentLinkedQueue.add(four);

        // four -> five -> three -> one -> two

        // then
        assertThat(concurrentLinkedQueue.toSortedList().toArray(new Expectation[0]), is(new Expectation[]{
            four,
            five,
            three,
            one,
            two,
        }));
        assertThat(concurrentLinkedQueue.keyMap().get(one.getId()), is(one));
        assertThat(concurrentLinkedQueue.keyMap().get(two.getId()), is(two));
        assertThat(concurrentLinkedQueue.keyMap().get(three.getId()), is(three));
        assertThat(concurrentLinkedQueue.keyMap().get(four.getId()), is(four));
        assertThat(concurrentLinkedQueue.keyMap().get(five.getId()), is(five));

        // when
        concurrentLinkedQueue.remove(three);
        assertThat(concurrentLinkedQueue.toSortedList().toArray(new Expectation[0]), is(new Expectation[]{
            four,
            five,
            one,
            two,
        }));
        assertThat(concurrentLinkedQueue.getByKey(five.getId()), is(Optional.of(five)));
        assertThat(concurrentLinkedQueue.getByKey(four.getId()), is(Optional.of(four)));
        assertThat(concurrentLinkedQueue.getByKey(three.getId()), is(Optional.empty()));
        assertThat(concurrentLinkedQueue.getByKey(two.getId()), is(Optional.of(two)));
        assertThat(concurrentLinkedQueue.getByKey(one.getId()), is(Optional.of(one)));
        assertThat(concurrentLinkedQueue.keyMap().get(three.getId()), is(nullValue()));

        // and
        concurrentLinkedQueue.remove(four);
        assertThat(concurrentLinkedQueue.toSortedList().toArray(new Expectation[0]), is(new Expectation[]{
            five,
            one,
            two,
        }));
        assertThat(concurrentLinkedQueue.getByKey(five.getId()), is(Optional.of(five)));
        assertThat(concurrentLinkedQueue.getByKey(four.getId()), is(Optional.empty()));
        assertThat(concurrentLinkedQueue.getByKey(three.getId()), is(Optional.empty()));
        assertThat(concurrentLinkedQueue.getByKey(two.getId()), is(Optional.of(two)));
        assertThat(concurrentLinkedQueue.getByKey(one.getId()), is(Optional.of(one)));
        assertThat(concurrentLinkedQueue.keyMap().get(three.getId()), is(nullValue()));
        assertThat(concurrentLinkedQueue.keyMap().get(four.getId()), is(nullValue()));

        // then
        assertThat(concurrentLinkedQueue.size(), is(3));
        assertThat(concurrentLinkedQueue.toSortedList(), contains(five, one, two));
    }

    @Test
    public void shouldFireEvictionListenerExactlyOncePerOverflowEvictedElement() {
        // given
        List<SortableExpectationId> evicted = new ArrayList<>();
        CircularPriorityQueue<String, SortableExpectationId, SortableExpectationId> queue = new CircularPriorityQueue<>(
            2,
            EXPECTATION_SORTABLE_PRIORITY_COMPARATOR,
            id -> id,
            id -> id.id
        );
        queue.setEvictionListener(evicted::add);

        SortableExpectationId one = new SortableExpectationId("1", 0, 0);
        SortableExpectationId two = new SortableExpectationId("2", 0, 0);
        SortableExpectationId three = new SortableExpectationId("3", 0, 0);
        SortableExpectationId four = new SortableExpectationId("4", 0, 0);

        // when - overflow past maxSize 2
        queue.add(one);
        queue.add(two);
        assertThat(evicted, is(empty()));
        queue.add(three); // evicts oldest "1"
        queue.add(four);  // evicts oldest "2"

        // then - each overflow-evicted element fired exactly once, in eviction order
        assertThat(evicted, contains(one, two));
        assertThat(queue.size(), is(2));
        assertThat(queue.toSortedList(), containsInAnyOrder(three, four));
    }

    @Test
    public void shouldNotFireEvictionListenerOnExplicitRemoveOrReplace() {
        // given
        AtomicInteger evictionCount = new AtomicInteger(0);
        CircularPriorityQueue<String, Expectation, SortableExpectationId> queue = new CircularPriorityQueue<>(
            5, EXPECTATION_SORTABLE_PRIORITY_COMPARATOR, Expectation::getSortableId, Expectation::getId);
        queue.setEvictionListener(e -> evictionCount.incrementAndGet());

        long ts = System.currentTimeMillis();
        Expectation a = when(request("a"), 0).withCreated(ts).withId("a");
        Expectation b = when(request("b"), 0).withCreated(ts + 1).withId("b");
        queue.add(a);
        queue.add(b);

        // when - explicit remove and replace (no overflow)
        queue.remove(a);
        Expectation bUpdated = when(request("b2"), 5).withCreated(ts + 1).withId("b");
        queue.replaceValue("b", bUpdated);

        // then - listener NOT invoked for remove/replace
        assertThat(evictionCount.get(), is(0));
        assertThat(queue.getByKey("b"), is(Optional.of(bUpdated)));
    }

    @Test
    public void shouldRestoreNoOpEvictionListenerWhenSetToNull() {
        // given
        AtomicInteger evictionCount = new AtomicInteger(0);
        CircularPriorityQueue<String, SortableExpectationId, SortableExpectationId> queue = new CircularPriorityQueue<>(
            1, EXPECTATION_SORTABLE_PRIORITY_COMPARATOR, id -> id, id -> id.id);
        queue.setEvictionListener(e -> evictionCount.incrementAndGet());
        queue.setEvictionListener(null); // restore no-op

        // when - force an overflow eviction
        queue.add(new SortableExpectationId("1", 0, 0));
        queue.add(new SortableExpectationId("2", 0, 0));

        // then - no NPE, no count (default no-op restored)
        assertThat(evictionCount.get(), is(0));
        assertThat(queue.size(), is(1));
    }

    @Test
    public void shouldPreserveEvictionPositionAcrossReplaceValue() {
        // given - a full queue of size 3 with a known eviction order one -> two -> three
        CircularPriorityQueue<String, Expectation, SortableExpectationId> queue = new CircularPriorityQueue<>(
            3, EXPECTATION_SORTABLE_PRIORITY_COMPARATOR, Expectation::getSortableId, Expectation::getId);

        long ts = System.currentTimeMillis();
        Expectation one = when(request("one"), 0).withCreated(ts + 1).withId("one");
        Expectation two = when(request("two"), 0).withCreated(ts + 2).withId("two");
        Expectation three = when(request("three"), 0).withCreated(ts + 3).withId("three");
        queue.add(one);
        queue.add(two);
        queue.add(three);

        // when - replace the OLDEST element (one) in place
        Expectation oneUpdated = when(request("one-v2"), 9).withCreated(ts + 1).withId("one");
        assertThat(queue.replaceValue("one", oneUpdated), is(true));
        // the live value is swapped
        assertThat(queue.getByKey("one"), is(Optional.of(oneUpdated)));

        // and - add a 4th element, forcing one eviction
        Expectation four = when(request("four"), 0).withCreated(ts + 4).withId("four");
        queue.add(four);

        // then - "one" is still the eviction victim despite the in-place replace,
        // proving replaceValue did NOT move it to the back of the insertion queue
        assertThat(queue.size(), is(3));
        assertThat(queue.getByKey("one"), is(Optional.empty()));
        assertThat(queue.getByKey("two").isPresent(), is(true));
        assertThat(queue.getByKey("three").isPresent(), is(true));
        assertThat(queue.getByKey("four").isPresent(), is(true));
    }

    @Test
    public void shouldFireEvictionListenerWithReplacedValueNotStaleOriginal() {
        // given - replaceValue swaps the live value; eviction must surface the LIVE value
        List<Expectation> evicted = new ArrayList<>();
        CircularPriorityQueue<String, Expectation, SortableExpectationId> queue = new CircularPriorityQueue<>(
            2, EXPECTATION_SORTABLE_PRIORITY_COMPARATOR, Expectation::getSortableId, Expectation::getId);
        queue.setEvictionListener(evicted::add);

        long ts = System.currentTimeMillis();
        Expectation one = when(request("one"), 0).withCreated(ts + 1).withId("one");
        Expectation two = when(request("two"), 0).withCreated(ts + 2).withId("two");
        queue.add(one);
        queue.add(two);

        // replace "one" in place, then overflow to evict it
        Expectation oneUpdated = when(request("one-v2"), 0).withCreated(ts + 1).withId("one");
        queue.replaceValue("one", oneUpdated);
        Expectation three = when(request("three"), 0).withCreated(ts + 3).withId("three");
        queue.add(three); // evicts "one", which now resolves to oneUpdated

        // then - the eviction listener received the live (replaced) value
        assertThat(evicted, contains(oneUpdated));
    }

    @Test
    public void shouldEvictEldestElementsImmediatelyWhenMaxSizeShrunk() {
        // given - a full queue recording every eviction
        List<SortableExpectationId> evicted = new ArrayList<>();
        CircularPriorityQueue<String, SortableExpectationId, SortableExpectationId> queue = new CircularPriorityQueue<>(
            5, EXPECTATION_SORTABLE_PRIORITY_COMPARATOR, id -> id, id -> id.id);
        queue.setEvictionListener(evicted::add);

        SortableExpectationId one = new SortableExpectationId("1", 0, 0);
        SortableExpectationId two = new SortableExpectationId("2", 0, 0);
        SortableExpectationId three = new SortableExpectationId("3", 0, 0);
        queue.add(one);
        queue.add(two);
        queue.add(three);
        assertThat(queue.size(), is(3));

        // when - the bound is shrunk below the current size (as a live maxExpectations change does)
        queue.setMaxSize(1);

        // then - the eldest elements are evicted straight away, without waiting for another add,
        // and are removed from every backing structure (size, sorted view and key lookup)
        assertThat(queue.size(), is(1));
        assertThat(queue.toSortedList(), contains(three));
        assertThat(queue.getByKey("1"), is(Optional.empty()));
        assertThat(queue.getByKey("2"), is(Optional.empty()));
        assertThat(queue.getByKey("3"), is(Optional.of(three)));
        assertThat(evicted, contains(one, two));
    }

    @Test
    public void shouldNotEvictWhenMaxSizeGrown() {
        // given
        List<SortableExpectationId> evicted = new ArrayList<>();
        CircularPriorityQueue<String, SortableExpectationId, SortableExpectationId> queue = new CircularPriorityQueue<>(
            2, EXPECTATION_SORTABLE_PRIORITY_COMPARATOR, id -> id, id -> id.id);
        queue.setEvictionListener(evicted::add);
        queue.add(new SortableExpectationId("1", 0, 0));
        queue.add(new SortableExpectationId("2", 0, 0));

        // when - the bound is raised and the new headroom used
        queue.setMaxSize(10);
        queue.add(new SortableExpectationId("3", 0, 0));

        // then
        assertThat(queue.size(), is(3));
        assertThat(evicted, is(empty()));
    }

    // The following tests pin the O(1) size-counter invariant introduced to stop
    // evictExcess() calling the O(n) ConcurrentLinkedQueue.size() on every add (which made
    // bulk expectation loading O(n^2)). size() must stay EXACTLY in step with the elements
    // actually held, across adds, removes, no-op removes and eviction.

    @Test
    public void shouldReportSizeAccuratelyAcrossAddsAndRemovesWithoutEviction() {
        // given - a bound far larger than the working set, so nothing is ever evicted
        CircularPriorityQueue<String, SortableExpectationId, SortableExpectationId> queue = new CircularPriorityQueue<>(
            1000, EXPECTATION_SORTABLE_PRIORITY_COMPARATOR, id -> id, id -> id.id);

        // when / then - size tracks each mutation exactly
        assertThat(queue.size(), is(0));
        queue.add(new SortableExpectationId("1", 0, 0));
        queue.add(new SortableExpectationId("2", 0, 0));
        queue.add(new SortableExpectationId("3", 0, 0));
        assertThat(queue.size(), is(3));

        queue.remove(new SortableExpectationId("2", 0, 0));
        assertThat(queue.size(), is(2));

        // removing an element that is not present must NOT change the count
        queue.remove(new SortableExpectationId("does-not-exist", 0, 0));
        assertThat(queue.size(), is(2));

        // removing the same element twice must decrement only once
        queue.remove(new SortableExpectationId("1", 0, 0));
        queue.remove(new SortableExpectationId("1", 0, 0));
        assertThat(queue.size(), is(1));

        queue.add(new SortableExpectationId("4", 0, 0));
        assertThat(queue.size(), is(2));
    }

    @Test
    public void shouldReportSizeAccuratelyAfterEviction() {
        // given
        CircularPriorityQueue<String, SortableExpectationId, SortableExpectationId> queue = new CircularPriorityQueue<>(
            3, EXPECTATION_SORTABLE_PRIORITY_COMPARATOR, id -> id, id -> id.id);

        // when - overflow the bound
        for (int i = 1; i <= 10; i++) {
            queue.add(new SortableExpectationId(String.valueOf(i), 0, 0));
        }

        // then - size never exceeds the bound and matches the live contents
        assertThat(queue.size(), is(3));
        assertThat(queue.toSortedList().size(), is(3));
        assertThat(queue.isEmpty(), is(false));
    }

    @Test
    public void shouldPreserveSortOrderAcrossLargeNoEvictionBatch() {
        // given - a large bound so the whole batch loads without eviction (the initializer shape)
        int n = 5000;
        CircularPriorityQueue<String, Expectation, SortableExpectationId> queue = new CircularPriorityQueue<>(
            n + 1, EXPECTATION_SORTABLE_PRIORITY_COMPARATOR, Expectation::getSortableId, Expectation::getId);
        long base = System.currentTimeMillis();

        // when - add in a deliberately scrambled order, with mixed priorities, all distinct created times
        List<Expectation> expected = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            // priority cycles 0..4, created strictly increases with i so the global order is well-defined
            Expectation expectation = when(request("/path-" + i), i % 5).withCreated(base + i).withId("id-" + i);
            expected.add(expectation);
        }
        // insert scrambled (odd indices first, then even) to prove insertion order does not leak into sort order
        for (int i = 1; i < n; i += 2) {
            queue.add(expected.get(i));
        }
        for (int i = 0; i < n; i += 2) {
            queue.add(expected.get(i));
        }

        // then - size is exact and the sorted view matches the comparator applied to all elements,
        // independent of the scrambled insertion order
        assertThat(queue.size(), is(n));
        List<Expectation> expectedSorted = new ArrayList<>(expected);
        expectedSorted.sort((a, b) -> EXPECTATION_SORTABLE_PRIORITY_COMPARATOR.compare(a.getSortableId(), b.getSortableId()));
        assertThat(queue.toSortedList(), is(expectedSorted));
    }

    // --- byte budget (maxExpectationsSizeInBytes shape) ---

    private static CircularPriorityQueue<String, SortableExpectationId, SortableExpectationId> byteBudgetQueue(long maxBytes, java.util.function.ToLongFunction<SortableExpectationId> weigher) {
        return new CircularPriorityQueue<>(
            1000, maxBytes, weigher,
            EXPECTATION_SORTABLE_PRIORITY_COMPARATOR,
            sid -> sid,
            sid -> sid.id
        );
    }

    @Test
    public void shouldEvictOldestToStayWithinByteBudget() {
        // given - budget of 250 bytes, each element weighs 100
        CircularPriorityQueue<String, SortableExpectationId, SortableExpectationId> queue = byteBudgetQueue(250L, e -> 100L);

        // when - three 100-byte elements would total 300 > 250
        queue.add(new SortableExpectationId("1", 0, 1));
        queue.add(new SortableExpectationId("2", 0, 2));
        queue.add(new SortableExpectationId("3", 0, 3));

        // then - oldest evicted, store stays under budget
        assertThat(queue.size(), is(2));
        assertThat(queue.getTotalBytes(), is(200L));
        assertThat(queue.getTotalBytes(), lessThanOrEqualTo(queue.getMaxBytes()));
        assertThat(queue.getByteEvictedCount(), is(1L));
        assertThat(queue.getByKey("1").isPresent(), is(false));
        assertThat(queue.getByKey("3").isPresent(), is(true));
    }

    @Test
    public void shouldNotEvictByBytesWhenBudgetIsHugeNegativeControl() {
        // given - identical adds to shouldEvictOldestToStayWithinByteBudget but an effectively unlimited budget
        CircularPriorityQueue<String, SortableExpectationId, SortableExpectationId> queue = byteBudgetQueue(1_000_000L, e -> 100L);

        // when
        queue.add(new SortableExpectationId("1", 0, 1));
        queue.add(new SortableExpectationId("2", 0, 2));
        queue.add(new SortableExpectationId("3", 0, 3));

        // then - nothing evicted; proves the eviction above was caused by the byte budget, not the count bound
        assertThat(queue.size(), is(3));
        assertThat(queue.getTotalBytes(), is(300L));
        assertThat(queue.getByteEvictedCount(), is(0L));
    }

    @Test
    public void shouldDisableByteBoundWhenMaxBytesIsZeroButStillTrackTotal() {
        // given - byte bound disabled with 0
        CircularPriorityQueue<String, SortableExpectationId, SortableExpectationId> queue = byteBudgetQueue(0L, e -> 100L);

        // when
        for (int i = 1; i <= 50; i++) {
            queue.add(new SortableExpectationId("" + i, 0, i));
        }

        // then - no byte eviction, but the weight is still accounted (so getTotalBytes reports a real figure)
        assertThat(queue.size(), is(50));
        assertThat(queue.getByteEvictedCount(), is(0L));
        assertThat(queue.getTotalBytes(), is(5000L));
    }

    @Test
    public void shouldRetainASingleOverBudgetElement() {
        // given - each element (100) is larger than the whole budget (50)
        CircularPriorityQueue<String, SortableExpectationId, SortableExpectationId> queue = byteBudgetQueue(50L, e -> 100L);

        // when
        queue.add(new SortableExpectationId("1", 0, 1));
        queue.add(new SortableExpectationId("2", 0, 2));

        // then - the incoming element is admitted rather than rejected; the queue never empties
        assertThat(queue.size(), is(1));
        assertThat(queue.getByKey("2").isPresent(), is(true));
    }

    @Test
    public void shouldKeepTotalBytesExactAcrossReplaceAndRemove() {
        // given - weigher reads the priority field so a replace can change an element's weight
        CircularPriorityQueue<String, SortableExpectationId, SortableExpectationId> queue = byteBudgetQueue(0L, sid -> sid.priority);
        queue.add(new SortableExpectationId("1", 100, 1));
        queue.add(new SortableExpectationId("2", 200, 2));
        assertThat(queue.getTotalBytes(), is(300L));

        // when - replace "1" with a lighter value
        queue.replaceValue("1", new SortableExpectationId("1", 50, 1));
        // then
        assertThat(queue.getTotalBytes(), is(250L));

        // when - remove "1" (subtracts the stored, replaced weight)
        queue.remove(new SortableExpectationId("1", 50, 1));
        // then
        assertThat(queue.getTotalBytes(), is(200L));
    }

    @Test
    public void shouldEvictImmediatelyWhenByteBudgetShrunk() {
        // given - budget disabled, three 100-byte elements admitted
        CircularPriorityQueue<String, SortableExpectationId, SortableExpectationId> queue = byteBudgetQueue(0L, e -> 100L);
        queue.add(new SortableExpectationId("1", 0, 1));
        queue.add(new SortableExpectationId("2", 0, 2));
        queue.add(new SortableExpectationId("3", 0, 3));
        assertThat(queue.getTotalBytes(), is(300L));

        // when - shrink the budget to 150
        queue.setMaxBytes(150L);

        // then - eldest evicted immediately until it fits
        assertThat(queue.size(), is(1));
        assertThat(queue.getTotalBytes(), is(100L));
        assertThat(queue.getByKey("3").isPresent(), is(true));
        assertThat(queue.getByteEvictedCount(), is(2L));
    }

}