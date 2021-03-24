///
/// Copyright © 2016-2021 The Thingsboard Authors
///
/// Licensed under the Apache License, Version 2.0 (the "License");
/// you may not use this file except in compliance with the License.
/// You may obtain a copy of the License at
///
///     http://www.apache.org/licenses/LICENSE-2.0
///
/// Unless required by applicable law or agreed to in writing, software
/// distributed under the License is distributed on an "AS IS" BASIS,
/// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
/// See the License for the specific language governing permissions and
/// limitations under the License.
///

import { Component, forwardRef, } from '@angular/core';
import { ControlValueAccessor, FormBuilder, FormGroup, NG_VALUE_ACCESSOR, Validators } from '@angular/forms';
import { Store } from '@ngrx/store';
import { AppState } from '@core/core.state';
import { INSTANCES_ID_VALUE_MAX, INSTANCES_ID_VALUE_MIN, KEY_REGEXP_NUMBER } from './profile-config.models';
import { DeviceProfileService } from '@core/http/device-profile.service';

@Component({
  selector: 'tb-profile-lwm2m-object-add-instances-list',
  templateUrl: './lwm2m-object-add-instances-list.component.html',
  providers: [{
    provide: NG_VALUE_ACCESSOR,
    useExisting: forwardRef(() => Lwm2mObjectAddInstancesListComponent),
    multi: true
  }]
})
export class Lwm2mObjectAddInstancesListComponent implements ControlValueAccessor {

  private disabled = false;
  private dirty = false;

  instancesListFormGroup: FormGroup;
  instancesId = new Set<number>();
  instanceIdValueMin = INSTANCES_ID_VALUE_MIN;
  instanceIdValueMax = INSTANCES_ID_VALUE_MAX;

  private propagateChange = (v: any) => { };

  constructor(private store: Store<AppState>,
              private deviceProfileService: DeviceProfileService,
              private fb: FormBuilder) {
    this.instancesListFormGroup = this.fb.group({
      instanceIdInput: [null, [
        Validators.min(this.instanceIdValueMin),
        Validators.max(this.instanceIdValueMax),
        Validators.pattern(KEY_REGEXP_NUMBER)]]
    });
  }

  registerOnChange(fn: any): void {
    this.propagateChange = fn;
  }

  registerOnTouched(fn: any): void {
  }

  setDisabledState(isDisabled: boolean): void {
    this.disabled = isDisabled;
    if (isDisabled) {
      this.instancesListFormGroup.disable({emitEvent: false});
    } else {
      this.instancesListFormGroup.enable({emitEvent: false});
    }
  }

  writeValue(value: Set<number>): void {
    if (value && value.size) {
      this.instancesId = value;
    }
    this.dirty = false;
  }

  add = (): void => {
    if (this.instancesListFormGroup.get('instanceIdInput').valid &&  Number.isFinite(Number(this.instanceId))) {
      this.instancesId.add(Number(this.instanceId));
      this.instancesListFormGroup.get('instanceIdInput').setValue(null);
      this.propagateChange(this.instancesId);
      this.dirty = true;
    }
  }

  remove = (object: number): void => {
    this.instancesId.delete(object);
    this.propagateChange(this.instancesId);
    this.dirty = true;
  }

  get instanceId(): number {
    return this.instancesListFormGroup.get('instanceIdInput').value;
  }
}
