package com.zilverline.es2
package domain

import util.NotNothing

private[domain] case class TrackedEventSource[+T](
  id: Identifier,
  revision: Revision,
  value: T,
  changes: IndexedSeq[DomainEvent] = IndexedSeq.empty
  ) {
  def record[A <: DomainEvent, B](event: A)(handler: Uncommitted[A] => B): TrackedEventSource[B] = {
    val updatedValue = handler(Uncommitted(id, event))
    copy(value = updatedValue, changes = changes :+ event)
  }
}

private[domain] case class Session(currentAggregateId: Identifier, aggregates: Aggregates, tracked: Map[Identifier, TrackedEventSource[Any]] = Map.empty) {
  def value[A: NotNothing](eventSourceId: Identifier): Option[A] = tracked.get(eventSourceId).map(_.value.asInstanceOf[A])

  def track(eventSourceId: Identifier, revision: Revision, value: Any): Session = {
    require(!tracked.contains(eventSourceId), "already tracking " + eventSourceId)
    copy(tracked = tracked.updated(eventSourceId, TrackedEventSource(eventSourceId, revision, value)))
  }

  def record[A <: DomainEvent, B](event: A)(handler: Uncommitted[A] => B): Reaction[B] = {
    val originalState = tracked.getOrElse(currentAggregateId, TrackedEventSource(currentAggregateId, InitialRevision, ()))
    val updatedState = originalState.record(event)(handler)
    Reaction(copy(tracked = tracked.updated(currentAggregateId, updatedState)), updatedState.value)
  }
}

case class Reaction[+A](session: Session, result: A)

trait Behavior[+A] {
  def apply(session: Session): Reaction[A]

  def map[B](f: A => B): Behavior[B] = Behavior {
    session =>
      val reaction = this(session)
      Reaction(reaction.session, f(reaction.result))
  }

  def flatMap[B](f: A => Behavior[B]): Behavior[B] = Behavior {
    session =>
      val reaction = this(session)
      f(reaction.result)(reaction.session)
  }

  def then[B](next: => Behavior[B]): Behavior[B] = flatMap(_ => next)
}

case class Reference[+A](aggregateId: Identifier) {
  def get: Behavior[A] = {
    import Behavior._
    getTrackedEventSource[A](aggregateId).flatMap {
      case Some(aggregate) =>
        pure(aggregate)
      case None =>
        getAggregate(aggregateId).flatMap {
          case Some(aggregate) =>
            trackEventSource(aggregate.id, aggregate.revision, aggregate.root.asInstanceOf[A])
          case None =>
            error("unknown aggregate <" + aggregateId + ">")
        }
    }
  }

  def run[A](f: => Behavior[A])(implicit aggregates: Aggregates): Reaction[A] = {
    f(Session(aggregateId, aggregates))
  }

  def run[B](f: A => Behavior[B])(implicit aggregates: Aggregates): Reaction[B] = {
    get.flatMap(f)(Session(aggregateId, aggregates))
  }
}

object Behavior {
  def pure[A](a: A): Behavior[A] = Behavior {Reaction(_, a)}

  def getTrackedEventSource[A](eventSourceId: Identifier): Behavior[Option[A]] = Behavior {
    session => Reaction(session, session.value[A](eventSourceId))
  }

  def trackEventSource[A](eventSourceId: Identifier, revision: Revision, value: A): Behavior[A] = Behavior {
    session => Reaction(session.track(eventSourceId, revision, value), value)
  }

  def record[A <: DomainEvent, B](event: A)(handler: Uncommitted[A] => B): Behavior[B] = Behavior {
    session => session.record(event)(handler)
  }

  def getAggregate(id: Identifier): Behavior[Option[Aggregate]] = Behavior {
    session => Reaction(session, session.aggregates.get(id))
  }

  def currentAggregateId: Behavior[Identifier] = Behavior {
    session => Reaction(session, session.currentAggregateId)
  }

  private[domain] def apply[A](f: Session => Reaction[A]): Behavior[A] = new Behavior[A] {
    def apply(session: Session) = f(session)
  }
}