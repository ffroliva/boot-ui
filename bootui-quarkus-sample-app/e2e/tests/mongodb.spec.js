import {expect, test} from '@playwright/test'
import {registerMongodbTests} from '../../../bootui-spring-sample-app/e2e/scenarios/mongodb.js'
registerMongodbTests(test, expect)
