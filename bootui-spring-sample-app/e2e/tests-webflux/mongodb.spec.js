import {expect, test} from '@playwright/test'
import {registerMongodbTests} from '../scenarios/mongodb.js'
registerMongodbTests(test, expect)
