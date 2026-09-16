import {expect, test} from '@playwright/test'
import {registerMongodbTests} from '../scenarios/mongodb.js'
registerMongodbTests(test, expect, {uiPath: '/host/dev-console', apiPath: '/host/internal/bootui-api'})
