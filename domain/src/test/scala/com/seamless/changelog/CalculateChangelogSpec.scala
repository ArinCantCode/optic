package com.seamless.changelog

import com.seamless.changelog.Changelog.{FieldShapeChange, ListItemTypeChanged}
import com.seamless.contexts.rfc.Events
import com.seamless.diff.JsonFileFixture
import com.seamless.serialization.EventSerialization
import org.scalatest.FunSpec

class CalculateChangelogSpec extends FunSpec with JsonFileFixture {

  def fixture(slug: String): Vector[Events.RfcEvent] = eventsFrom(slug)

  it("can prepare for changelog generation") {
    val f = fixture("todo")
    val input = CalculateChangelog.prepare(f, 20)
    assert(input.headPaths.size == 2 && input.historicalPaths.size == 1)
  }

  it("can calculate added paths") {
    val f = fixture("todo")
    val input = CalculateChangelog.prepare(f, 20)
    val addedPaths = CalculateChangelog.computeAddedPaths(input)
    assert(Vector(
      Changelog.AddedRequest("/todos/:todoId", "PATCH", "request_bJyzwU0FfJ"),
    ) == addedPaths)
  }


  it("can calculate changelog for shape") {
    val f = fixture("basic")
    implicit val input = CalculateChangelog.prepare(f, 9)
    val changelog = CalculateShapeChangelog.changeLogForShape("shape_xK4xBcs1TE", "shape_xK4xBcs1TE", InResponse("", 200))
    assert(changelog.size == 4)
  }

  it("can calculate for request") {
    val f = fixture("basic")
    implicit val input = CalculateChangelog.prepare(f, 9)
    val fullChangelog = CalculateChangelog.generate(input)
    assert(fullChangelog.updatedRequests.head._2.size == 4)
  }

  it("can calculate for request with polymorphism") {
    val f = fixture("polymorphism")
    implicit val input = CalculateChangelog.prepare(f, 12)
    val fullChangelog = CalculateChangelog.generate(input)

    fullChangelog.updatedRequests.head._2.forall(_.isInstanceOf[FieldShapeChange])
  }

  it("can calculate for request with list item changes") {
    val f = fixture("list")
    implicit val input = CalculateChangelog.prepare(f, 9)
    val fullChangelog = CalculateChangelog.generate(input)

    assert(fullChangelog.updatedRequests.head._2.size == 1)
    val change = fullChangelog.updatedRequests.head._2.head
    assert(change.isInstanceOf[ListItemTypeChanged])
    assert(change.asInstanceOf[ListItemTypeChanged].oldType == "List of Number")
    assert(change.asInstanceOf[ListItemTypeChanged].newType == "List of Number , String or List of Boolean")
  }

}
