// GENERATED FILE!
// VERSION-INFO: com.valtech.source.ag.evf.codegen.Dispatcher
// DATE/TIME-INFO: 17.05.04 09:36:44

package com.valtech.source.dependometer.app.controller.pack;

import com.valtech.source.ag.evf.Dispatcher;
import com.valtech.source.ag.evf.EventIf;

final class DispatcherPackageCycleCollectedEvent extends Dispatcher
{
   protected void dispatch(Object handler, EventIf event)
   {
      assert handler != null;
      assert event != null;
      ((HandlePackageCycleCollectedEventIf)handler)
         .handleEvent((com.valtech.source.dependometer.app.controller.pack.PackageCycleCollectedEvent)event);
   }
}
